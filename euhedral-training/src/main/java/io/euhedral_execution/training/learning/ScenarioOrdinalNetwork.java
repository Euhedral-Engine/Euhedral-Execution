package io.euhedral_execution.training.learning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import ai.djl.Device;
import ai.djl.Model;
import ai.djl.engine.Engine;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Activation;
import ai.djl.nn.Parameter;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.core.Linear;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.EasyTrain;
import ai.djl.training.ParameterStore;
import ai.djl.training.Trainer;
import ai.djl.training.dataset.ArrayDataset;
import ai.djl.training.dataset.Batch;
import ai.djl.training.initializer.XavierInitializer;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.training.tracker.Tracker;
import io.euhedral_execution.training.learning.config.ScenarioMemberSeeds;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.data.BalancedScenarioOrdinalLoss;
import io.euhedral_execution.training.learning.data.ScenarioLearningMatrix;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.metadata.MemberMetadata;
import io.euhedral_execution.training.learning.metadata.ScenarioModelMetadata;
import io.euhedral_execution.training.learning.output.EvaluationSummary;
import io.euhedral_execution.training.learning.output.TrainingHistoryEntry;
import io.euhedral_execution.training.learning.utils.DeterministicBatchSampler;

final class ScenarioOrdinalNetwork implements OrdinalMember {

    static final String ENGINE_NAME = "PyTorch";
    private static final Object TRAINING_MONITOR = new Object();

    static {
        System.setProperty("ai.djl.default_engine",
                System.getProperty("ai.djl.default_engine", ENGINE_NAME));
    }

    static Device resolveDevice(String requested) {
        Engine engine = Engine.getEngine(ENGINE_NAME);
        if (requested.equals("auto")) {
            return engine.getGpuCount() > 0 ? Device.gpu(0) : Device.cpu();
        }
        return Device.fromName(requested, engine);
    }

    static ScenarioOrdinalNetwork load(Path memberDirectory, ScenarioFeatureSet featureSet,
            MemberMetadata metadata, Device device) throws IOException {
        ScenarioOrdinalNetwork network = new ScenarioOrdinalNetwork(featureSet.width(), device);
        try {
            network.model.load(memberDirectory, ScenarioModelMetadata.MEMBER_MODEL_NAME);
            network.verifyProperties(featureSet, metadata);
            return network;
        } catch (Exception error) {
            network.close();
            throw new IOException("Failed to load scenario ordinal member " + metadata.index(),
                    error);
        }
    }

    static TrainingResult train(ScenarioLearningMatrix fitting, ScenarioLearningMatrix validation,
            ScenarioFeatureSet featureSet, ScenarioTrainingConfig config, Device device,
            String trainingKind, String foldId, int memberIndex, Path memberDirectory)
            throws Exception {
        long memberSeed =
                ScenarioMemberSeeds.derive(config.modelSeed(), trainingKind, featureSet, foldId,
                        memberIndex);
        synchronized (TRAINING_MONITOR) {
            Engine.getEngine(ENGINE_NAME).setRandomSeed(ScenarioMemberSeeds.engineSeed(memberSeed));
            ScenarioOrdinalNetwork network = new ScenarioOrdinalNetwork(featureSet.width(), device);
            try {
                return network.fit(fitting, validation, featureSet, config, trainingKind, foldId,
                        memberIndex, memberSeed, memberDirectory);
            } catch (Throwable error) {
                network.close();
                throw error;
            }
        }
    }

    private static SequentialBlock buildBlock() {
        return new SequentialBlock().add(Linear.builder().setUnits(128).build())
                .add(Activation::gelu).add(Linear.builder().setUnits(96).build())
                .add(Activation::gelu).add(Linear.builder().setUnits(48).build())
                .add(Activation::gelu).add(Linear.builder().setUnits(9).build());
    }

    private final int featureWidth;
    private final Device device;
    private final Model model;
    private final ParameterStore inferenceParameters;
    private boolean closed;

    private ScenarioOrdinalNetwork(int featureWidth, Device device) {
        this.featureWidth = featureWidth;
        this.device = device;
        model = Model.newInstance(ScenarioModelMetadata.MEMBER_MODEL_NAME, device, ENGINE_NAME);
        model.setBlock(buildBlock());
        inferenceParameters = new ParameterStore(model.getNDManager(), false);
    }

    private TrainingResult fit(ScenarioLearningMatrix fitting, ScenarioLearningMatrix validation,
            ScenarioFeatureSet featureSet, ScenarioTrainingConfig config, String trainingKind,
            String foldId, int memberIndex, long memberSeed, Path memberDirectory)
            throws Exception {
        if (fitting.featureWidth() != featureWidth || validation.featureWidth() != featureWidth
                || featureSet.width() != featureWidth) {
            throw new IllegalArgumentException("Feature widths disagree");
        }
        Files.createDirectories(memberDirectory);
        BalancedScenarioOrdinalLoss.ClassBalance balance = BalancedScenarioOrdinalLoss.fit(fitting);
        BalancedScenarioOrdinalLoss loss =
                new BalancedScenarioOrdinalLoss(model.getNDManager(), balance,
                        config.labelSmoothing());
        Optimizer optimizer =
                Optimizer.adamW().optLearningRateTracker(Tracker.fixed(config.learningRate()))
                        .optWeightDecays(config.weightDecay()).optClipGrad(5.0f).build();
        DefaultTrainingConfig training = new DefaultTrainingConfig(loss).optOptimizer(optimizer)
                .optDevices(new Device[] {device})
                .optInitializer(new XavierInitializer(), Parameter.Type.WEIGHT);
        int effectiveBatch = StrictMath.min(config.batchSize(), fitting.rows());
        if (effectiveBatch <= 0) {
            effectiveBatch = StrictMath.min(device.isGpu() ? 4_096 : 512, fitting.rows());
        }
        DeterministicBatchSampler sampler =
                new DeterministicBatchSampler(fitting.rows(), memberSeed);
        ArrayList<TrainingHistoryEntry> history = new ArrayList<>();
        double bestMae = Double.POSITIVE_INFINITY;
        double bestSpearman = Double.NEGATIVE_INFINITY;
        double bestBce = Double.POSITIVE_INFINITY;
        int bestEpoch = -1;
        int staleEpochs = 0;
        try (Trainer trainer = model.newTrainer(training)) {
            trainer.initialize(new Shape(effectiveBatch, featureWidth));
            for (int epoch = 0; epoch < config.maxEpochs(); epoch++) {
                int[] order = sampler.order(epoch);
                try (NDManager epochManager = model.getNDManager().newSubManager(device)) {
                    ArrayDataset dataset = dataset(epochManager, fitting, order, effectiveBatch);
                    for (Batch batch : trainer.iterateDataset(dataset)) {
                        try (batch) {
                            EasyTrain.trainBatch(trainer, batch);
                            trainer.step();
                        }
                    }
                }
                float[] logits = evaluate(trainer, validation);
                EvaluationSummary metrics =
                        ScenarioModelEvaluator.evaluateMatrix("EARLY_STOP", foldId, featureSet,
                                validation, logits);
                double macroMae = metrics.macroMae().orElseThrow(
                        () -> new InsufficientScenarioLearningDataException(
                                "Validation macro MAE is unavailable"));
                double macroSpearman = metrics.macroSpearman().orElse(Double.NEGATIVE_INFINITY);
                double bce = BalancedScenarioOrdinalLoss.weightedBce(logits, validation, balance,
                        config.labelSmoothing());
                boolean improved = macroMae < bestMae - 1.0e-9
                        || StrictMath.abs(macroMae - bestMae) <= 1.0e-9 && (
                        macroSpearman > bestSpearman + 1.0e-9
                                || StrictMath.abs(macroSpearman - bestSpearman) <= 1.0e-9
                                && bce < bestBce);
                history.add(new TrainingHistoryEntry(trainingKind, foldId, featureSet, memberIndex,
                        memberSeed, epoch, macroMae, metrics.macroSpearman(), bce, false));
                if (improved) {
                    bestMae = macroMae;
                    bestSpearman = macroSpearman;
                    bestBce = bce;
                    bestEpoch = epoch;
                    staleEpochs = 0;
                    save(memberDirectory, featureSet, memberIndex, memberSeed);
                } else if (++staleEpochs >= config.patience()) {
                    break;
                }
            }
        }
        if (bestEpoch < 0) {
            throw new IllegalStateException("No model epoch was selected");
        }
        close();
        ScenarioOrdinalNetwork best = load(memberDirectory, featureSet,
                new MemberMetadata(memberIndex, memberSeed, bestEpoch,
                        MemberMetadata.expectedPath(memberIndex), "0".repeat(64)), device);
        ArrayList<TrainingHistoryEntry> selectedHistory = new ArrayList<>(history.size());
        for (TrainingHistoryEntry entry : history) {
            selectedHistory.add(new TrainingHistoryEntry(entry.trainingKind(), entry.foldId(),
                    entry.featureSet(), entry.memberIndex(), entry.memberSeed(), entry.epoch(),
                    entry.validationMacroMae(), entry.validationMacroSpearman(),
                    entry.validationWeightedBce(), entry.epoch() == bestEpoch));
        }
        return new TrainingResult(best, memberSeed, bestEpoch, List.copyOf(selectedHistory));
    }

    private ArrayDataset dataset(NDManager manager, ScenarioLearningMatrix matrix, int[] order,
            int batchSize) {
        float[] originalFeatures = matrix.features();
        float[] originalLabels = matrix.ordinalLabels();
        float[] originalWeights = matrix.rowWeights();
        float[] features = new float[originalFeatures.length];
        float[] labels = new float[originalLabels.length];
        float[] weights = new float[originalWeights.length];
        for (int target = 0; target < order.length; target++) {
            int source = order[target];
            System.arraycopy(originalFeatures, source * featureWidth, features,
                    target * featureWidth, featureWidth);
            System.arraycopy(originalLabels, source * 9, labels, target * 9, 9);
            weights[target] = originalWeights[source];
        }
        NDArray data = manager.create(features, new Shape(matrix.rows(), featureWidth));
        NDArray target = manager.create(labels, new Shape(matrix.rows(), 9));
        NDArray rowWeight = manager.create(weights, new Shape(matrix.rows(), 1));
        return new ArrayDataset.Builder().setData(data).optLabels(target, rowWeight)
                .setSampling(batchSize, false).optPrefetchNumber(0).optDevice(device).build();
    }

    private float[] evaluate(Trainer trainer, ScenarioLearningMatrix matrix) {
        float[] features = matrix.features();
        try (NDManager manager = model.getNDManager().newSubManager(device)) {
            NDArray input = manager.create(features, new Shape(matrix.rows(), featureWidth));
            NDArray output = trainer.evaluate(new NDList(input)).singletonOrThrow();
            return output.toFloatArray();
        }
    }

    private void save(Path directory, ScenarioFeatureSet featureSet, int memberIndex,
            long memberSeed) throws IOException {
        model.setProperty("Epoch", "0");
        model.setProperty("artifact_type", ScenarioModelMetadata.ARTIFACT_TYPE);
        model.setProperty("schema_version", Integer.toString(ScenarioModelMetadata.SCHEMA_VERSION));
        model.setProperty("objective_version", ScenarioModelMetadata.OBJECTIVE_VERSION);
        model.setProperty("feature_schema_id", featureSet.schemaId());
        model.setProperty("feature_width", Integer.toString(featureWidth));
        model.setProperty("output_width", Integer.toString(ScenarioModelMetadata.OUTPUT_WIDTH));
        model.setProperty("member_index", Integer.toString(memberIndex));
        model.setProperty("member_seed_hex", "%016x".formatted(memberSeed));
        model.setProperty("architecture", ScenarioModelMetadata.ARCHITECTURE);
        model.save(directory, ScenarioModelMetadata.MEMBER_MODEL_NAME);
    }

    private void verifyProperties(ScenarioFeatureSet featureSet, MemberMetadata metadata) {
        requireProperty("Epoch", "0");
        requireProperty("artifact_type", ScenarioModelMetadata.ARTIFACT_TYPE);
        requireProperty("schema_version", Integer.toString(ScenarioModelMetadata.SCHEMA_VERSION));
        requireProperty("objective_version", ScenarioModelMetadata.OBJECTIVE_VERSION);
        requireProperty("feature_schema_id", featureSet.schemaId());
        requireProperty("feature_width", Integer.toString(featureWidth));
        requireProperty("output_width", "9");
        requireProperty("member_index", Integer.toString(metadata.index()));
        requireProperty("member_seed_hex", "%016x".formatted(metadata.seed()));
        requireProperty("architecture", ScenarioModelMetadata.ARCHITECTURE);
    }

    private void requireProperty(String name, String expected) {
        if (!expected.equals(model.getProperty(name))) {
            throw new IllegalArgumentException("Member property mismatch: " + name);
        }
    }

    @Override
    public int featureWidth() {
        return featureWidth;
    }

    @Override
    public void predictLogits(float[] features, int rows, float[] destination) {
        ensureOpen();
        if (rows < 0 || features.length != rows * featureWidth || destination.length != rows * 9) {
            throw new IllegalArgumentException("Invalid inference buffers");
        }
        if (rows == 0) {
            return;
        }
        try (NDManager manager = model.getNDManager().newSubManager(device)) {
            NDArray input = manager.create(features, new Shape(rows, featureWidth));
            NDArray output = model.getBlock().forward(inferenceParameters, new NDList(input), false)
                    .singletonOrThrow();
            float[] values = output.toFloatArray();
            System.arraycopy(values, 0, destination, 0, values.length);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Ordinal member is closed");
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            model.close();
        }
    }

    record TrainingResult(ScenarioOrdinalNetwork member, long seed, int bestEpoch,
                          List<TrainingHistoryEntry> history) {

    }
}
