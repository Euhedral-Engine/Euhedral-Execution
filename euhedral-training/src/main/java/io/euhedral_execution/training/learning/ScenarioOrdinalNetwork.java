package io.euhedral_execution.training.learning;

import io.euhedral_execution.training.learning.config.ScenarioMemberSeeds;
import io.euhedral_execution.training.learning.config.ScenarioTrainingConfig;
import io.euhedral_execution.training.learning.data.BalancedScenarioOrdinalLoss;
import io.euhedral_execution.training.learning.data.ScenarioLearningMatrix;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.metadata.MemberMetadata;
import io.euhedral_execution.training.learning.metadata.ScenarioModelMetadata;
import io.euhedral_execution.training.learning.network_operations.TensorFlowNetwork;
import io.euhedral_execution.training.learning.output.EvaluationSummary;
import io.euhedral_execution.training.learning.output.TrainingHistoryEntry;
import io.euhedral_execution.training.learning.utils.DeterministicBatchSampler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class ScenarioOrdinalNetwork implements OrdinalMember {

    static final String ENGINE_NAME = "TensorFlow";
    private static final Object TRAINING_MONITOR = new Object();

    static TrainingDevice resolveDevice(String requested) {
        return TrainingDevice.resolve(requested);
    }

    static ScenarioOrdinalNetwork load(
            Path memberDirectory, ScenarioFeatureSet featureSet, MemberMetadata metadata, TrainingDevice device)
            throws IOException {
        ScenarioOrdinalNetwork network = new ScenarioOrdinalNetwork(featureSet.width(), device);
        try {
            network.tfNetwork.load(memberDirectory, ScenarioModelMetadata.MEMBER_MODEL_NAME);
            network.verifyProperties(featureSet, metadata);
            return network;
        } catch (Exception error) {
            network.close();
            throw new IOException("Failed to load scenario ordinal member " + metadata.index(), error);
        }
    }

    static TrainingResult train(
            ScenarioLearningMatrix fitting,
            ScenarioLearningMatrix validation,
            ScenarioFeatureSet featureSet,
            ScenarioTrainingConfig config,
            TrainingDevice device,
            String trainingKind,
            String foldId,
            int memberIndex,
            Path memberDirectory)
            throws Exception {
        long memberSeed = ScenarioMemberSeeds.derive(config.modelSeed(), trainingKind, featureSet, foldId, memberIndex);
        synchronized (TRAINING_MONITOR) {
            ScenarioOrdinalNetwork network = new ScenarioOrdinalNetwork(featureSet.width(), device);
            try {
                return network.fit(
                        fitting,
                        validation,
                        featureSet,
                        config,
                        trainingKind,
                        foldId,
                        memberIndex,
                        memberSeed,
                        memberDirectory);
            } catch (Throwable error) {
                network.close();
                throw error;
            }
        }
    }

    private final int featureWidth;
    private final TrainingDevice device;
    private final TensorFlowNetwork tfNetwork;
    private boolean closed;

    private ScenarioOrdinalNetwork(int featureWidth, TrainingDevice device) {
        this.featureWidth = featureWidth;
        this.device = device;
        this.tfNetwork = new TensorFlowNetwork(featureWidth, device);
    }

    private TrainingResult fit(
            ScenarioLearningMatrix fitting,
            ScenarioLearningMatrix validation,
            ScenarioFeatureSet featureSet,
            ScenarioTrainingConfig config,
            String trainingKind,
            String foldId,
            int memberIndex,
            long memberSeed,
            Path memberDirectory)
            throws Exception {
        if (fitting.featureWidth() != featureWidth
                || validation.featureWidth() != featureWidth
                || featureSet.width() != featureWidth) {
            throw new IllegalArgumentException("Feature widths disagree");
        }
        Files.createDirectories(memberDirectory);
        BalancedScenarioOrdinalLoss.ClassBalance balance = BalancedScenarioOrdinalLoss.fit(fitting);
        int effectiveBatch = StrictMath.min(config.batchSize(), fitting.rows());
        if (effectiveBatch <= 0) {
            effectiveBatch = StrictMath.min(device.isGpu() ? 4_096 : 512, fitting.rows());
        }
        DeterministicBatchSampler sampler = new DeterministicBatchSampler(fitting.rows(), memberSeed);
        ArrayList<TrainingHistoryEntry> history = new ArrayList<>();
        double bestMae = Double.POSITIVE_INFINITY;
        double bestSpearman = Double.NEGATIVE_INFINITY;
        double bestBce = Double.POSITIVE_INFINITY;
        int bestEpoch = -1;
        int staleEpochs = 0;

        try (TensorFlowNetwork trainerNetwork =
                new TensorFlowNetwork(featureWidth, config.learningRate(), config.labelSmoothing(), device)) {
            float[] fittingFeatures = fitting.features();
            float[] fittingLabels = fitting.ordinalLabels();
            float[] fittingWeights = fitting.rowWeights();

            for (int epoch = 0; epoch < config.maxEpochs(); epoch++) {
                int[] order = sampler.order(epoch);
                int totalRows = order.length;
                for (int start = 0; start < totalRows; start += effectiveBatch) {
                    int batchSize = StrictMath.min(effectiveBatch, totalRows - start);
                    float[] batchFeatures = new float[batchSize * featureWidth];
                    float[] batchLabels = new float[batchSize * 9];
                    float[] batchWeights = new float[batchSize];

                    for (int i = 0; i < batchSize; i++) {
                        int row = order[start + i];
                        System.arraycopy(
                                fittingFeatures, row * featureWidth, batchFeatures, i * featureWidth, featureWidth);
                        System.arraycopy(fittingLabels, row * 9, batchLabels, i * 9, 9);
                        batchWeights[i] = fittingWeights[row];
                    }

                    trainerNetwork.trainBatch(
                            batchFeatures,
                            batchLabels,
                            batchWeights,
                            balance.positiveWeights(),
                            balance.negativeWeights(),
                            batchSize);
                }

                float[] logits = evaluate(trainerNetwork, validation);
                EvaluationSummary metrics =
                        ScenarioModelEvaluator.evaluateMatrix("EARLY_STOP", foldId, featureSet, validation, logits);
                double macroMae = metrics.macroMae()
                        .orElseThrow(() ->
                                new InsufficientScenarioLearningDataException("Validation macro MAE is unavailable"));
                double macroSpearman = metrics.macroSpearman().orElse(Double.NEGATIVE_INFINITY);
                double bce =
                        BalancedScenarioOrdinalLoss.weightedBce(logits, validation, balance, config.labelSmoothing());
                boolean improved = macroMae < bestMae - 1.0e-9
                        || StrictMath.abs(macroMae - bestMae) <= 1.0e-9
                                && (macroSpearman > bestSpearman + 1.0e-9
                                        || StrictMath.abs(macroSpearman - bestSpearman) <= 1.0e-9 && bce < bestBce);
                history.add(new TrainingHistoryEntry(
                        trainingKind,
                        foldId,
                        featureSet,
                        memberIndex,
                        memberSeed,
                        epoch,
                        macroMae,
                        metrics.macroSpearman(),
                        bce,
                        false));
                if (improved) {
                    bestMae = macroMae;
                    bestSpearman = macroSpearman;
                    bestBce = bce;
                    bestEpoch = epoch;
                    staleEpochs = 0;
                    save(trainerNetwork, memberDirectory, featureSet, memberIndex, memberSeed);
                } else if (++staleEpochs >= config.patience()) {
                    break;
                }
            }
        }

        if (bestEpoch < 0) {
            throw new IllegalStateException("No model epoch was selected");
        }
        close();
        ScenarioOrdinalNetwork best = load(
                memberDirectory,
                featureSet,
                new MemberMetadata(
                        memberIndex, memberSeed, bestEpoch, MemberMetadata.expectedPath(memberIndex), "0".repeat(64)),
                device);
        ArrayList<TrainingHistoryEntry> selectedHistory = new ArrayList<>(history.size());
        for (TrainingHistoryEntry entry : history) {
            selectedHistory.add(new TrainingHistoryEntry(
                    entry.trainingKind(),
                    entry.foldId(),
                    entry.featureSet(),
                    entry.memberIndex(),
                    entry.memberSeed(),
                    entry.epoch(),
                    entry.validationMacroMae(),
                    entry.validationMacroSpearman(),
                    entry.validationWeightedBce(),
                    entry.epoch() == bestEpoch));
        }
        return new TrainingResult(best, memberSeed, bestEpoch, List.copyOf(selectedHistory));
    }

    private float[] evaluate(TensorFlowNetwork network, ScenarioLearningMatrix matrix) {
        float[] features = matrix.features();
        float[] logits = new float[matrix.rows() * 9];
        network.predictLogits(features, matrix.rows(), logits);
        return logits;
    }

    private void save(
            TensorFlowNetwork network, Path directory, ScenarioFeatureSet featureSet, int memberIndex, long memberSeed)
            throws IOException {
        network.setProperty("Epoch", "0");
        network.setProperty("artifact_type", ScenarioModelMetadata.ARTIFACT_TYPE);
        network.setProperty("schema_version", Integer.toString(ScenarioModelMetadata.SCHEMA_VERSION));
        network.setProperty("objective_version", ScenarioModelMetadata.OBJECTIVE_VERSION);
        network.setProperty("feature_schema_id", featureSet.schemaId());
        network.setProperty("feature_width", Integer.toString(featureWidth));
        network.setProperty("output_width", Integer.toString(ScenarioModelMetadata.OUTPUT_WIDTH));
        network.setProperty("member_index", Integer.toString(memberIndex));
        network.setProperty("member_seed_hex", "%016x".formatted(memberSeed));
        network.setProperty("architecture", ScenarioModelMetadata.ARCHITECTURE);
        network.save(directory, ScenarioModelMetadata.MEMBER_MODEL_NAME);
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
        if (!expected.equals(tfNetwork.getProperty(name))) {
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
        tfNetwork.predictLogits(features, rows, destination);
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
            tfNetwork.close();
        }
    }

    record TrainingResult(
            ScenarioOrdinalNetwork member, long seed, int bestEpoch, List<TrainingHistoryEntry> history) {}
}
