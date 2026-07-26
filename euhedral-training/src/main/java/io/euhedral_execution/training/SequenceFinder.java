package io.euhedral_execution.training;

import io.euhedral_execution.data_structures.queues.PlainQueue;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.networks.PolicyOrdinalNetwork;
import io.euhedral_execution.training.utils.BenchmarkOutputReader;
import io.euhedral_execution.training.utils.BenchmarkOutputWriter;
import io.euhedral_execution.training.utils.CommonFunctions;
import io.euhedral_execution.training.utils.PolicyRanking;
import io.euhedral_execution.training.utils.VectorGrouper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import org.apache.commons.math4.legacy.random.SobolSequenceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SequenceFinder implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SequenceFinder.class);
    private static final int VECTOR_SIZE = PolicyOrdinalNetwork.INPUT_SIZE;

    private PolicyOrdinalNetwork learner;
    private final List<Sample> trainingSet = new ArrayList<>();
    private final List<Sample> validationSet = new ArrayList<>();
    private final List<Sample> testSet = new ArrayList<>();
    private double[][] ordinalThresholds;

    public SequenceFinder(String[] args) throws Exception {
        Path data = requiredPathProperty("data");
        Path model = optionalPathProperty("model");
        this.learner = loadOrCreate(model);

        if (System.getProperty("generate") != null) {
            Path output = Path.of(System.getProperty("candidate.output", "output/temp_data"));
            int count = Integer.getInteger("candidate.count", 32_768);
            int sobolSkip = Integer.getInteger("candidate.sobolSkip", 131_072);
            generate(data, output, count, sobolSkip);
            return;
        }

        loadTrainingData(data);
        Path modelOutput = Path.of(System.getProperty("model.output", "output/model/best"));
        train(modelOutput);
    }

    private SequenceFinder(Path model) throws Exception {
        this.learner = loadOrCreate(model);
    }

    public static Path train(Path data, Path startingModel, Path modelOutput) throws Exception {
        try (SequenceFinder finder = new SequenceFinder(startingModel)) {
            finder.loadTrainingData(data);
            finder.train(modelOutput);
        }
        return modelOutput;
    }

    public static Path generateCandidates(Path historicalData, Path model, Path output,
            int candidateCount) throws Exception {
        return generateCandidates(historicalData, model, output, candidateCount,
                Integer.getInteger("candidate.sobolSkip", 131_072));
    }

    public static Path generateCandidates(Path historicalData, Path model, Path output,
            int candidateCount, int sobolSkip) throws Exception {
        try (SequenceFinder finder = new SequenceFinder(model)) {
            finder.generate(historicalData, output, candidateCount, sobolSkip);
        }
        return output;
    }

    private static PolicyOrdinalNetwork loadOrCreate(Path model) throws Exception {
        if (model == null || !Files.exists(model)) {
            return new PolicyOrdinalNetwork();
        }
        if (!Files.isDirectory(model)) {
            throw new IllegalArgumentException(
                    "Expected a DJL ordinal model directory, not a legacy DL4J .bin file: " + model);
        }
        return new PolicyOrdinalNetwork(model);
    }

    private static Path requiredPathProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required system property -D" + name);
        }
        return Path.of(value);
    }

    private static Path optionalPathProperty(String name) {
        String value = System.getProperty(name);
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private void loadTrainingData(Path path) throws Exception {
        this.trainingSet.clear();
        this.validationSet.clear();
        this.testSet.clear();

        int[] choice = new int[10];
        choice[8] = 1;
        choice[9] = 2;
        long splitSeed = Long.getLong("training.seed", 123L);

        try (BenchmarkOutputReader reader = new BenchmarkOutputReader(path)) {
            while (true) {
                double[] vector = reader.readDoubleArray();
                if (vector == null) {
                    break;
                }
                double[] results = reader.readDoubleArray();
                if (results == null) {
                    throw new IllegalStateException("Missing labels after vector in " + path);
                }
                if (vector.length != VECTOR_SIZE
                        || results.length != PolicyRanking.QUANTILE_COUNT) {
                    throw new IllegalStateException(
                            "Expected a 28-weight vector followed by five quantiles in " + path);
                }

                long hash = HasherApi.getHash(vector, splitSeed);
                int bucket = choice[(int) Math.unsignedMultiplyHigh(hash, 10)];
                Sample sample = new Sample(vector, results, hash);

                if (bucket == 1) {
                    this.validationSet.add(sample);
                } else if (bucket == 2) {
                    this.testSet.add(sample);
                } else {
                    this.trainingSet.add(sample);
                }
            }
        }

        if (this.trainingSet.size() < 10 || this.validationSet.isEmpty()
                || this.testSet.isEmpty()) {
            throw new IllegalStateException(
                    "Training data must produce non-empty train, validation, and test partitions");
        }

        List<double[]> trainingQuantiles = new ArrayList<>(this.trainingSet.size());
        for (Sample sample : this.trainingSet) {
            trainingQuantiles.add(sample.quantiles());
        }
        this.ordinalThresholds = PolicyRanking.buildDecileThresholds(trainingQuantiles);

        LOGGER.info("Loaded {} training, {} validation, and {} test vectors",
                this.trainingSet.size(), this.validationSet.size(), this.testSet.size());
        LOGGER.info("Ordinal labels calibrated from the training partition; top-decile threshold={}",
                Arrays.toString(this.ordinalThresholds[PolicyRanking.ORDINAL_OUTPUTS - 1]));
    }

    public void generate() throws Exception {
        generate(requiredPathProperty("data"),
                Path.of(System.getProperty("candidate.output", "output/temp_data")),
                Integer.getInteger("candidate.count", 32_768),
                Integer.getInteger("candidate.sobolSkip", 131_072));
    }

    public void generate(Path historicalData, Path output, int candidateCount) throws Exception {
        generate(historicalData, output, candidateCount,
                Integer.getInteger("candidate.sobolSkip", 131_072));
    }

    public void generate(Path historicalData, Path output, int candidateCount, int sobolSkip)
            throws Exception {
        if (candidateCount < 3) {
            throw new IllegalArgumentException("candidateCount must be at least 3");
        }
        if (sobolSkip < 0) {
            throw new IllegalArgumentException("sobolSkip must not be negative");
        }

        int kClusters = Integer.getInteger("candidate.clusters", 28);
        int maxClusterIterations = Integer.getInteger("candidate.clusterIterations", 500);
        VectorGrouper grouper = new VectorGrouper(kClusters, maxClusterIterations, historicalData);
        List<VectorGrouper.ClusterScore> rankedClusters = grouper.getClusters();
        double[] bestClusterCentroid = rankedClusters.isEmpty()
                ? null
                : rankedClusters.getLast().cluster.centroid().getPoint();

        SobolSequenceGenerator generator = new SobolSequenceGenerator(VECTOR_SIZE);
        generator.skipTo(sobolSkip);

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        int pureExplorationCount = Math.max(1, candidateCount / 16);
        int localExploitationCount = Math.max(1, candidateCount / 16);
        int screenedCandidateCount = candidateCount - pureExplorationCount - localExploitationCount;

        PriorityQueue<Candidate> topCandidates =
                new PriorityQueue<>(screenedCandidateCount + 1);
        int batchSize = Integer.getInteger("candidate.batchSize",
                this.learner.recommendedInferenceBatchSize());
        long screenLimit = Long.getLong("candidate.screenLimit", 1L << 21);
        if (batchSize <= 0 || screenLimit <= 0) {
            throw new IllegalArgumentException("candidate batch and screen limits must be positive");
        }

        LOGGER.info(
                "Screening {} vectors from Sobol index {} for {} model-selected candidates on {}",
                screenLimit, sobolSkip, screenedCandidateCount, this.learner.getDevice());
        PlainQueue<Candidate> recycle = new PlainQueue<>(batchSize);
        NumberFormat format = NumberFormat.getNumberInstance();
        float[] featureBatch = new float[batchSize * VECTOR_SIZE];
        float[] scoreBatch = new float[batchSize];
        double[][] vectorBatch = new double[batchSize][];

        long screened = 0;
        while (screened < screenLimit) {
            int currentBatch = (int) Math.min(batchSize, screenLimit - screened);
            for (int row = 0; row < currentBatch; row++) {
                double[] vector = generator.get();
                CommonFunctions.normalizeSobolVector(vector);
                vectorBatch[row] = vector;
                copyVectorToFloat(vector, featureBatch, row * VECTOR_SIZE);
            }

            this.learner.predictScores(featureBatch, currentBatch, scoreBatch);
            for (int row = 0; row < currentBatch; row++) {
                Candidate candidate = recycle.poll();
                if (candidate == null) {
                    candidate = new Candidate(vectorBatch[row], scoreBatch[row]);
                } else {
                    System.arraycopy(vectorBatch[row], 0, candidate.vector, 0, VECTOR_SIZE);
                    candidate.score = scoreBatch[row];
                }
                topCandidates.add(candidate);
            }

            while (topCandidates.size() > screenedCandidateCount) {
                recycle.offer(topCandidates.poll());
            }
            screened += currentBatch;
            LOGGER.info("Screening progress: {} / {}", format.format(screened),
                    format.format(screenLimit));
        }

        List<Candidate> rankedCandidates = new ArrayList<>(topCandidates.size());
        while (!topCandidates.isEmpty()) {
            rankedCandidates.add(topCandidates.poll());
        }
        Collections.reverse(rankedCandidates);

        try (BenchmarkOutputWriter writer = new BenchmarkOutputWriter(output)) {
            for (Candidate candidate : rankedCandidates) {
                writer.spaceSeparatedWriteLine(candidate.vector);
            }

            LOGGER.info("Injecting {} global exploration vectors", pureExplorationCount);
            for (int i = 0; i < pureExplorationCount; i++) {
                double[] vector = generator.get();
                CommonFunctions.normalizeSobolVector(vector);
                writer.spaceSeparatedWriteLine(vector);
            }

            LOGGER.info("Injecting {} local exploitation vectors", localExploitationCount);
            Random noiseGenerator = new Random(Long.getLong("candidate.seed", 123L) ^ sobolSkip);
            for (int i = 0; i < localExploitationCount; i++) {
                double[] explorationVector;
                if (bestClusterCentroid == null) {
                    explorationVector = generator.get();
                    CommonFunctions.normalizeSobolVector(explorationVector);
                } else {
                    explorationVector = Arrays.copyOf(bestClusterCentroid, VECTOR_SIZE);
                    double sigma = Double.parseDouble(
                            System.getProperty("candidate.localSigma", "0.05"));
                    for (int j = 0; j < VECTOR_SIZE; j++) {
                        explorationVector[j] += noiseGenerator.nextGaussian() * sigma;
                    }
                    CommonFunctions.normalizePolicyVector(explorationVector);
                }
                writer.spaceSeparatedWriteLine(explorationVector);
            }
        }
        LOGGER.info("Generated {} vectors at {}", candidateCount, output.toAbsolutePath());
    }

    public void train() throws Exception {
        train(Path.of(System.getProperty("model.output", "output/model/best")));
    }

    public void train(Path modelPath) throws Exception {
        if (Files.exists(modelPath) && !Files.isDirectory(modelPath)) {
            throw new IllegalArgumentException("model.output must be a directory: " + modelPath);
        }
        Files.createDirectories(modelPath);

        TrainingMatrix train = matrix(this.trainingSet);
        TrainingMatrix validation = matrix(this.validationSet);
        int batchSize = Integer.getInteger("training.batchSize",
                this.learner.recommendedTrainingBatchSize());

        this.learner = this.learner.trainWithEarlyStopping(
                train.features(), train.labels(), train.rows(),
                validation.features(), validation.labels(), validation.rows(),
                modelPath,
                Integer.getInteger("training.maxEpochs", 250),
                Integer.getInteger("training.patience", 20),
                batchSize);

        runFinalEvaluation();

        Path latestMarker = modelPath.getParent() == null
                ? Paths.get("latest")
                : modelPath.getParent().resolve("latest");
        Files.writeString(latestMarker, modelPath.toAbsolutePath().toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private TrainingMatrix matrix(List<Sample> samples) {
        float[] features = new float[samples.size() * VECTOR_SIZE];
        float[] labels = new float[samples.size() * PolicyRanking.ORDINAL_OUTPUTS];
        for (int row = 0; row < samples.size(); row++) {
            Sample sample = samples.get(row);
            copyVectorToFloat(sample.vector(), features, row * VECTOR_SIZE);
            PolicyRanking.encodeOrdinal(sample.quantiles(), this.ordinalThresholds, labels,
                    row * PolicyRanking.ORDINAL_OUTPUTS);
        }
        return new TrainingMatrix(features, labels, samples.size());
    }

    private void runFinalEvaluation() {
        float[] features = new float[this.testSet.size() * VECTOR_SIZE];
        float[] scores = new float[this.testSet.size()];
        for (int row = 0; row < this.testSet.size(); row++) {
            copyVectorToFloat(this.testSet.get(row).vector(), features, row * VECTOR_SIZE);
        }
        this.learner.predictScores(features, this.testSet.size(), scores);

        List<Sample> actualRanked = new ArrayList<>(this.testSet);
        actualRanked.sort((first, second) ->
                PolicyRanking.compare(first.quantiles(), second.quantiles()));

        List<ScoredSample> predictedRanked = new ArrayList<>(this.testSet.size());
        for (int row = 0; row < this.testSet.size(); row++) {
            predictedRanked.add(new ScoredSample(this.testSet.get(row), scores[row]));
        }
        predictedRanked.sort(Comparator.comparingDouble(ScoredSample::score));

        int topK = Math.max(1, this.testSet.size() / 10);
        Set<Long> topActualHashes = new HashSet<>(topK * 2);
        for (int i = actualRanked.size() - topK; i < actualRanked.size(); i++) {
            topActualHashes.add(actualRanked.get(i).hash());
        }

        int matches = 0;
        for (int i = predictedRanked.size() - topK; i < predictedRanked.size(); i++) {
            if (topActualHashes.contains(predictedRanked.get(i).sample().hash())) {
                matches++;
            }
        }

        double matchPercentage = ((double) matches / topK) * 100.0;
        LOGGER.info("============ FINAL TEST SET EVALUATION ============");
        LOGGER.info("Top-10% Candidate Ranking Precision: {}%", matchPercentage);
        LOGGER.info("Selected {} of the actual top {} policies", matches, topK);
        LOGGER.info("====================================================");
    }

    private static void copyVectorToFloat(double[] source, float[] destination, int offset) {
        for (int feature = 0; feature < VECTOR_SIZE; feature++) {
            destination[offset + feature] = (float) source[feature];
        }
    }

    @Override
    public void close() {
        if (this.learner != null) {
            this.learner.close();
            this.learner = null;
        }
    }

    private record Sample(double[] vector, double[] quantiles, long hash) {
    }

    private record TrainingMatrix(float[] features, float[] labels, int rows) {
    }

    private record ScoredSample(Sample sample, float score) {
    }

    private static final class Candidate implements Comparable<Candidate> {

        private final double[] vector;
        private float score;

        private Candidate(double[] vector, float score) {
            this.vector = vector;
            this.score = score;
        }

        @Override
        public int compareTo(Candidate other) {
            return Float.compare(this.score, other.score);
        }
    }
}
