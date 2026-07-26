package io.euhedral_execution.training.networks;

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
import ai.djl.training.loss.Loss;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.training.tracker.Tracker;
import ai.djl.util.cuda.CudaUtils;
import io.euhedral_execution.training.utils.PolicyRanking;
import java.io.IOException;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compact ordinal classifier for scheduler policies.
 *
 * <p>The network predicts nine cumulative decile logits rather than attempting to regress noisy
 * throughput quantiles. The final logit directly answers whether a policy belongs in the top
 * decile, while the lower thresholds provide dense ordinal supervision and a smoother ranking
 * signal for candidate screening.</p>
 */
public final class PolicyOrdinalNetwork implements AutoCloseable {

    public static final int INPUT_SIZE = 28;
    public static final int OUTPUT_SIZE = PolicyRanking.ORDINAL_OUTPUTS;

    private static final Logger LOGGER = LoggerFactory.getLogger(PolicyOrdinalNetwork.class);
    private static final String ENGINE = "PyTorch";
    private static final String MODEL_NAME = "euhedral-policy-ranker";

    static {
        System.setProperty("ai.djl.default_engine",
                System.getProperty("ai.djl.default_engine", ENGINE));
    }

    private final Device device;
    private final Model model;
    private final ParameterStore inferenceParameters;

    public PolicyOrdinalNetwork() {
        this(resolveDevice(), false, null);
    }

    public PolicyOrdinalNetwork(Path modelDirectory) throws IOException {
        this(resolveDevice(), true, modelDirectory);
    }

    private PolicyOrdinalNetwork(Device device, boolean load, Path modelDirectory) {
        this.device = device;
        this.model = Model.newInstance(MODEL_NAME, device, ENGINE);
        this.model.setBlock(buildBlock());

        if (load) {
            try {
                this.model.load(modelDirectory, MODEL_NAME);
            } catch (Exception e) {
                this.model.close();
                throw new IllegalArgumentException(
                        "Failed to load ordinal policy model from " + modelDirectory, e);
            }
        }

        this.inferenceParameters = new ParameterStore(this.model.getNDManager(), false);
        Engine engine = Engine.getEngine(ENGINE);
        LOGGER.info("Policy classifier engine={} version={} device={}", ENGINE,
                engine.getVersion(), device);
    }

    private static SequentialBlock buildBlock() {
        // The policy vector is only 28 values. A shallow tapered MLP has ample capacity without
        // wasting screening time or encouraging memorization of benchmark noise. Widths are aligned
        // to GPU-friendly multiples while the final layer remains the nine ordinal thresholds.
        return new SequentialBlock()
                .add(Linear.builder().setUnits(128).build())
                .add(Activation::gelu)
                .add(Linear.builder().setUnits(96).build())
                .add(Activation::gelu)
                .add(Linear.builder().setUnits(48).build())
                .add(Activation::gelu)
                .add(Linear.builder().setUnits(OUTPUT_SIZE).build());
    }

    public Device getDevice() {
        return this.device;
    }

    public boolean isGpu() {
        return this.device.isGpu();
    }

    public int recommendedTrainingBatchSize() {
        return this.isGpu() ? 4_096 : 512;
    }

    public int recommendedInferenceBatchSize() {
        return this.isGpu() ? 65_536 : 16_384;
    }

    /**
     * Scores a contiguous row-major float matrix without creating per-row feature or prediction
     * arrays.
     */
    public void predictScores(float[] features, int rows, float[] destination) {
        if (rows < 0 || features.length < rows * INPUT_SIZE || destination.length < rows) {
            throw new IllegalArgumentException("Invalid policy inference buffers");
        }
        if (rows == 0) {
            return;
        }

        int activeLength = rows * INPUT_SIZE;
        float[] activeFeatures = features.length == activeLength
                ? features
                : Arrays.copyOf(features, activeLength);
        try (NDManager scope = this.model.getNDManager().newSubManager()) {
            NDArray input = scope.create(activeFeatures, new Shape(rows, INPUT_SIZE));
            NDArray output = this.model.getBlock()
                    .forward(this.inferenceParameters, new NDList(input), false)
                    .singletonOrThrow();
            float[] logits = output.toFloatArray();
            for (int row = 0; row < rows; row++) {
                destination[row] = rankingScore(logits, row * OUTPUT_SIZE);
            }
        }
    }

    public PolicyOrdinalNetwork trainWithEarlyStopping(
            float[] trainFeatures, float[] trainLabels, int trainRows,
            float[] validationFeatures, float[] validationLabels, int validationRows,
            Path checkpointDirectory, int maxEpochs, int patience, int batchSize) throws Exception {

        validateMatrix(trainFeatures, trainLabels, trainRows);
        validateMatrix(validationFeatures, validationLabels, validationRows);
        if (maxEpochs <= 0 || patience <= 0 || batchSize <= 0) {
            throw new IllegalArgumentException("Epochs, patience, and batch size must be positive");
        }

        Files.createDirectories(checkpointDirectory);
        float learningRate = Float.parseFloat(
                System.getProperty("training.learningRate", "0.001"));
        float weightDecay = Float.parseFloat(
                System.getProperty("training.weightDecay", "0.0001"));
        float topDecileWeight = Float.parseFloat(
                System.getProperty("training.topDecileWeight", "2.0"));
        float labelSmoothing = Float.parseFloat(
                System.getProperty("training.labelSmoothing", "0.02"));
        if (labelSmoothing < 0.0f || labelSmoothing >= 0.5f) {
            throw new IllegalArgumentException("training.labelSmoothing must be in [0, 0.5)");
        }

        ClassWeights classWeights = classWeights(trainLabels, trainRows, topDecileWeight);
        NDManager manager = this.model.getNDManager();
        Loss loss = new BalancedOrdinalLoss(manager, classWeights, labelSmoothing);
        Optimizer optimizer = Optimizer.adamW()
                .optLearningRateTracker(Tracker.fixed(learningRate))
                .optWeightDecays(weightDecay)
                .optClipGrad(5.0f)
                .build();
        DefaultTrainingConfig config = new DefaultTrainingConfig(loss)
                .optOptimizer(optimizer)
                .optDevices(new Device[]{this.device})
                .optInitializer(new XavierInitializer(), Parameter.Type.WEIGHT);

        double bestPrecision = Double.NEGATIVE_INFINITY;
        double bestLoss = Double.POSITIVE_INFINITY;
        int epochsWithoutImprovement = 0;
        int effectiveBatch = Math.min(batchSize, trainRows);

        NDArray trainInput = manager.create(trainFeatures, new Shape(trainRows, INPUT_SIZE));
        NDArray trainTarget = manager.create(trainLabels, new Shape(trainRows, OUTPUT_SIZE));
        NDArray validationInput = manager.create(validationFeatures,
                new Shape(validationRows, INPUT_SIZE));

        ArrayDataset trainingSet = new ArrayDataset.Builder()
                .setData(trainInput)
                .optLabels(trainTarget)
                .setSampling(effectiveBatch, true)
                .optPrefetchNumber(this.isGpu() ? 0 : 2)
                .optDevice(this.device)
                .build();

        LOGGER.info(
                "Training ordinal policy classifier: trainRows={} validationRows={} batch={} lr={} weightDecay={} topDecileWeight={} labelSmoothing={}",
                trainRows, validationRows, effectiveBatch, learningRate, weightDecay,
                topDecileWeight, labelSmoothing);
        LOGGER.info("Ordinal positive class rates={}",
                Arrays.toString(classWeights.positiveRates()));

        try (Trainer trainer = this.model.newTrainer(config)) {
            trainer.initialize(new Shape(effectiveBatch, INPUT_SIZE));

            for (int epoch = 0; epoch < maxEpochs; epoch++) {
                long start = System.nanoTime();
                long batches = 0;
                for (Batch batch : trainer.iterateDataset(trainingSet)) {
                    try (batch) {
                        EasyTrain.trainBatch(trainer, batch);
                        trainer.step();
                        batches++;
                    }
                }

                ValidationMetrics metrics = validationMetrics(trainer, validationInput,
                        validationLabels, validationRows, classWeights);
                LOGGER.info(
                        "Epoch {} | batches={} | validation weighted BCE={} | validation top-10 precision={} | elapsed={} ms",
                        epoch, batches, metrics.loss(), metrics.precisionAtTen(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));

                boolean precisionImproved = metrics.precisionAtTen() > bestPrecision + 1.0e-9;
                boolean tiedPrecision =
                        Math.abs(metrics.precisionAtTen() - bestPrecision) <= 1.0e-9;
                if (precisionImproved || (tiedPrecision && metrics.loss() < bestLoss)) {
                    bestPrecision = metrics.precisionAtTen();
                    bestLoss = metrics.loss();
                    epochsWithoutImprovement = 0;
                    save(checkpointDirectory);
                } else if (++epochsWithoutImprovement >= patience) {
                    LOGGER.info(
                            "Early stopping at epoch {}. Best top-10 precision={} weighted BCE={}",
                            epoch, bestPrecision, bestLoss);
                    break;
                }
            }
        }

        PolicyOrdinalNetwork best = new PolicyOrdinalNetwork(checkpointDirectory);
        this.close();
        return best;
    }

    private ValidationMetrics validationMetrics(Trainer trainer, NDArray features, float[] labels,
            int rows, ClassWeights classWeights) {
        NDArray output = trainer.evaluate(new NDList(features)).singletonOrThrow();
        float[] logits;
        try {
            logits = output.toFloatArray();
        } finally {
            output.close();
        }

        double loss = weightedBinaryCrossEntropy(logits, labels, classWeights);
        float[] scores = new float[rows];
        for (int row = 0; row < rows; row++) {
            scores[row] = rankingScore(logits, row * OUTPUT_SIZE);
        }
        return new ValidationMetrics(loss, precisionAtTen(scores, labels, rows));
    }

    private static double weightedBinaryCrossEntropy(float[] logits, float[] labels,
            ClassWeights classWeights) {
        double total = 0;
        for (int row = 0; row < logits.length / OUTPUT_SIZE; row++) {
            int offset = row * OUTPUT_SIZE;
            for (int output = 0; output < OUTPUT_SIZE; output++) {
                double logit = logits[offset + output];
                double label = labels[offset + output];
                double baseLoss = Math.max(logit, 0.0) - logit * label
                        + Math.log1p(Math.exp(-Math.abs(logit)));
                double weight = label > 0.5
                        ? classWeights.positive()[output]
                        : classWeights.negative()[output];
                total += baseLoss * weight;
            }
        }
        return total / logits.length;
    }

    private static double precisionAtTen(float[] scores, float[] labels, int rows) {
        int selected = Math.max(1, rows / 10);
        float[] sorted = Arrays.copyOf(scores, rows);
        Arrays.sort(sorted);
        float threshold = sorted[rows - selected];

        int truePositives = 0;
        int accepted = 0;
        for (int row = 0; row < rows && accepted < selected; row++) {
            if (scores[row] >= threshold) {
                if (labels[row * OUTPUT_SIZE + OUTPUT_SIZE - 1] > 0.5f) {
                    truePositives++;
                }
                accepted++;
            }
        }
        return (double) truePositives / selected;
    }

    private static float rankingScore(float[] logits, int offset) {
        // Project independently learned cumulative probabilities back onto a monotonic sequence.
        // A policy cannot be more likely to clear the 90th percentile than the 80th percentile.
        double expectedDecile = 0;
        double cumulativeProbability = 1.0;
        for (int output = 0; output < OUTPUT_SIZE; output++) {
            cumulativeProbability = Math.min(cumulativeProbability,
                    PolicyRanking.sigmoid(logits[offset + output]));
            expectedDecile += cumulativeProbability;
        }
        return (float) (expectedDecile + 4.0 * cumulativeProbability);
    }

    private static ClassWeights classWeights(float[] labels, int rows, float topDecileWeight) {
        float[] positiveRates = new float[OUTPUT_SIZE];
        for (int row = 0; row < rows; row++) {
            int offset = row * OUTPUT_SIZE;
            for (int output = 0; output < OUTPUT_SIZE; output++) {
                positiveRates[output] += labels[offset + output];
            }
        }

        float[] positive = new float[OUTPUT_SIZE];
        float[] negative = new float[OUTPUT_SIZE];
        float minimumRate = 1.0f / rows;
        for (int output = 0; output < OUTPUT_SIZE; output++) {
            float rate = positiveRates[output] / rows;
            rate = Math.max(minimumRate, Math.min(1.0f - minimumRate, rate));
            positiveRates[output] = rate;
            positive[output] = 0.5f / rate;
            negative[output] = 0.5f / (1.0f - rate);
        }
        positive[OUTPUT_SIZE - 1] *= topDecileWeight;
        negative[OUTPUT_SIZE - 1] *= topDecileWeight;
        return new ClassWeights(positive, negative, positiveRates);
    }

    private void save(Path modelDirectory) throws IOException {
        this.model.setProperty("Epoch", "0");
        this.model.setProperty("objective", "cumulative-decile-classification");
        this.model.setProperty("architecture", "28-128-96-48-9-gelu");
        this.model.save(modelDirectory, MODEL_NAME);
    }

    public static void printEnvironment() {
        Engine engine = Engine.getEngine(ENGINE);
        int gpuCount = engine.getGpuCount();
        System.out.println("DJL engine: " + ENGINE + " " + engine.getVersion());
        System.out.println("GPU count: " + gpuCount);
        if (gpuCount == 0) {
            System.out.println("Default training device: CPU");
            return;
        }

        System.out.println("CUDA runtime: " + CudaUtils.getCudaVersionString());
        for (int gpu = 0; gpu < gpuCount; gpu++) {
            MemoryUsage memory = CudaUtils.getGpuMemory(Device.gpu(gpu));
            System.out.println("GPU " + gpu + " compute capability: "
                    + CudaUtils.getComputeCapability(gpu));
            System.out.println("GPU " + gpu + " memory committed/max: "
                    + memory.getCommitted() + "/" + memory.getMax());
        }
    }

    private static Device resolveDevice() {
        Engine engine = Engine.getEngine(ENGINE);
        String requested = System.getProperty("training.device", "auto")
                .trim().toLowerCase(Locale.ROOT);
        if (requested.equals("auto")) {
            return engine.getGpuCount() > 0 ? Device.gpu(0) : Device.cpu();
        }
        return Device.fromName(requested, engine);
    }

    private static void validateMatrix(float[] features, float[] labels, int rows) {
        if (rows <= 0 || features.length != rows * INPUT_SIZE
                || labels.length != rows * OUTPUT_SIZE) {
            throw new IllegalArgumentException("Invalid ordinal training matrix");
        }
    }

    @Override
    public void close() {
        this.model.close();
    }

    private record ValidationMetrics(double loss, double precisionAtTen) {
    }

    private record ClassWeights(float[] positive, float[] negative, float[] positiveRates) {
    }

    private static final class BalancedOrdinalLoss extends Loss {

        private final NDArray positiveWeights;
        private final NDArray negativeWeights;
        private final float labelSmoothing;

        private BalancedOrdinalLoss(NDManager manager, ClassWeights classWeights,
                float labelSmoothing) {
            super("BalancedOrdinalBinaryCrossEntropy");
            this.labelSmoothing = labelSmoothing;
            this.positiveWeights = manager.create(classWeights.positive()).reshape(1, OUTPUT_SIZE);
            this.negativeWeights = manager.create(classWeights.negative()).reshape(1, OUTPUT_SIZE);
        }

        @Override
        public NDArray evaluate(NDList labels, NDList predictions) {
            NDArray hardLabel = labels.singletonOrThrow();
            NDArray logit = predictions.singletonOrThrow();
            NDArray sampleWeight = hardLabel.mul(this.positiveWeights)
                    .add(hardLabel.neg().add(1.0f).mul(this.negativeWeights));
            NDArray target = this.labelSmoothing == 0.0f
                    ? hardLabel
                    : hardLabel.mul(1.0f - 2.0f * this.labelSmoothing)
                            .add(this.labelSmoothing);
            NDArray stableBce = Activation.relu(logit)
                    .sub(logit.mul(target))
                    .add(Activation.softPlus(logit.abs().neg()));
            return stableBce.mul(sampleWeight).mean();
        }
    }
}
