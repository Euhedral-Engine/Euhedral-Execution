package io.euhedral_execution.training;

import static io.euhedral_execution.training.utils.CommonFunctions.round;

import io.euhedral_execution.data_structures.queues.PlainQueue;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.networks.DL4JVectorScoringNetwork;
import io.euhedral_execution.training.utils.BenchmarkOutputReader;
import io.euhedral_execution.training.utils.BenchmarkOutputWriter;
import io.euhedral_execution.training.utils.CommonFunctions;
import io.euhedral_execution.training.utils.VectorGrouper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.commons.math4.legacy.random.SobolSequenceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SequenceFinder {

    private static final Logger LOGGER = LoggerFactory.getLogger(SequenceFinder.class);

    private DL4JVectorScoringNetwork learner;
    private final List<double[][]> trainingSet = new ArrayList<>();
    private final List<double[][]> validationSet = new ArrayList<>();
    private final List<double[][]> testSet = new ArrayList<>();

    public SequenceFinder(String[] args) throws Exception {
        boolean gen = System.getProperty("generate") != null;
        String loadModel = System.getProperty("model");

        if (loadModel != null && !loadModel.isBlank()) {
            this.learner = new DL4JVectorScoringNetwork(loadModel);
        } else {
            // Use DL4J-backed network; keep a similar capacity to the legacy network
            this.learner = new DL4JVectorScoringNetwork(new long[]{28, 128, 128, 64, 5});
        }
        if(gen) {
            generate();
            return;
        }

        Path path = Path.of(System.getProperty("data"));
        try (BenchmarkOutputReader reader = new BenchmarkOutputReader(path)) {
            int[] choice = new int[10]; // 80% Train
            choice[8] = 1;              // 10% Validation
            choice[9] = 2;              // 10% Test
            long seed = ThreadLocalRandom.current().nextLong();

            while (true) {
                double[] vector = reader.readDoubleArray();
                if (vector == null) {
                    break;
                }

                double[] results = reader.readDoubleArray();

                // Uniform bucketing using xxHash64
                int bucket = choice[(int) Math.unsignedMultiplyHigh(HasherApi.mix(seed++), 10)];
                double[][] dataPair = new double[][]{vector, results};

                if (bucket == 1) {
                    this.validationSet.add(dataPair);
                } else if (bucket == 2) {
                    this.testSet.add(dataPair);
                } else {
                    this.trainingSet.add(dataPair);
                }
            }
        }
        train();
    }

    public void generate() throws Exception {
        int kClusters = 28;
        int maxClusterIterations = 500;
        Path historicalData = Path.of(System.getProperty("data"));

        VectorGrouper grouper = new VectorGrouper(kClusters, maxClusterIterations, historicalData);
        List<VectorGrouper.ClusterScore> rankedClusters = grouper.getClusters();
        double[] bestClusterCentroid = rankedClusters.getLast().cluster.centroid().getPoint();

        SobolSequenceGenerator generator = new SobolSequenceGenerator(28);
        generator.skipTo(131_072);

        Path out = Paths.get("output/temp_data");
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }

        int cap = 32_768 - 4096;
        PriorityQueue<Candidate> topCandidates = new PriorityQueue<>(cap + 1);

        LOGGER.info("Screening vectors...");

        double[][] batch = new double[16_384][];
        PlainQueue<Candidate> recycle = new PlainQueue<>(16_384);
        NumberFormat format = NumberFormat.getNumberInstance();
        for (int i = 4096; i < Math.pow(2, 21); i += 16_384) {
            LOGGER.info("Progress: {} / {}", format.format(i), format.format((int) Math.pow(2, 21)));
            for(int j = 0; j < 16_384; j++) {
                batch[j] = generator.get();
                CommonFunctions.normalizeSobolVector(batch[j]);
            }
            double[][] predictions = this.learner.predict(batch);
            for(int j = 0; j < 16_384; j++) {
                Candidate candidate = recycle.poll();
                if(candidate == null) {
                    candidate = new Candidate(batch[j], predictions[j]);
                } else {
                    System.arraycopy(batch[j], 0, candidate.vector, 0, batch[j].length);
                    System.arraycopy(predictions[j], 0, candidate.quantiles, 0, predictions[j].length);
                }
                topCandidates.add(candidate);
            }

            while (topCandidates.size() > cap) {
                recycle.offer(topCandidates.poll());
            }
        }

        try (BenchmarkOutputWriter writer = new BenchmarkOutputWriter(out)) {

            while (!topCandidates.isEmpty()) {
                writer.spaceSeparatedWriteLine(topCandidates.poll().vector);
            }

            int pureExplorationCount = 2048;
            int localExploitationCount = 2048;

            LOGGER.info("Injecting global exploration vectors.");
            for (int i = 0; i < pureExplorationCount; i++) {
                double[] vector = generator.get();
                CommonFunctions.normalizeSobolVector(vector);
                writer.spaceSeparatedWriteLine(vector);
            }

            LOGGER.info("Injecting cluster-targeted exploitation vectors.");
            Random noiseGenerator = new Random();
            for (int i = 0; i < localExploitationCount; i++) {
                double[] explorationVector = new double[28];
                for (int j = 0; j < 28; j++) {
                    double noise = noiseGenerator.nextGaussian() * 0.05;
                    explorationVector[j] = bestClusterCentroid[j] + noise;
                }
                CommonFunctions.normalizeSobolVector(explorationVector);

                writer.spaceSeparatedWriteLine(explorationVector);
            }
        }
        LOGGER.info(
                "Successfully generated 32,768 vectors for the next active learning execution loop.");
    }

    public void train() throws Exception {
        Path modelPath = Paths.get("output/model/best.bin");
        if (modelPath.getParent() != null) {
            Files.createDirectories(modelPath.getParent());
        }

        // Convert training/validation sets to double arrays for batch training
        int trainSize = this.trainingSet.size();
        int valSize = this.validationSet.size();

        double[][] trainInputs = new double[trainSize][];
        double[][] trainTargets = new double[trainSize][];
        for (int i = 0; i < trainSize; i++) {
            trainInputs[i] = this.trainingSet.get(i)[0];
            trainTargets[i] = this.trainingSet.get(i)[1];
        }

        double[][] valInputs = new double[valSize][];
        double[][] valTargets = new double[valSize][];
        for (int i = 0; i < valSize; i++) {
            valInputs[i] = this.validationSet.get(i)[0];
            valTargets[i] = this.validationSet.get(i)[1];
        }

        // Train with early stopping and automatic model checkpointing
        this.learner = this.learner.trainWithEarlyStopping(
                trainInputs, trainTargets,
                valInputs, valTargets,
                modelPath.toString(),
                500,    // maxEpochs
                15,     // patience
                64      // batchSize
        );

        runFinalEvaluation();

        // Write marker file for compatibility
        Path latestMarker = Paths.get("output/model/latest");
        if (latestMarker.getParent() != null) {
            Files.createDirectories(latestMarker.getParent());
        }
        Files.writeString(latestMarker, modelPath.toString(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private double evaluateSetLoss(List<double[][]> dataset) {
        double totalLoss = 0;
        for (var v : dataset) {
            double loss = 0;
            double[] prediction = this.learner.predict(v[0]);
            for(int i = 0; i < prediction.length; i++) {
                double error = v[1][i] - prediction[i];
                loss += (error * error);
            }
            totalLoss += loss / prediction.length;
        }
        return totalLoss / dataset.size();
    }

    private void runFinalEvaluation() {
        double totalAbsoluteError = 0;
        List<Candidate> actualRanked = new ArrayList<>();
        List<Candidate> predictedRanked = new ArrayList<>();

        for (var v : this.testSet) {
            double[] actual = v[1];
            double[] prediction = this.learner.predict(v[0]);

            double mae = 0;
            for(int i = 0; i < actual.length; i++) {
                double error = Math.abs(actual[i] - prediction[i]);
                mae += error;
            }
            totalAbsoluteError += mae / actual.length;

            actualRanked.add(new Candidate(v[0], v[1]));
            predictedRanked.add(new Candidate(v[0], prediction));
        }

        Collections.sort(actualRanked);
        Collections.sort(predictedRanked);

        // Quantify Top 10% configuration ranking accuracy
        int topK = Math.max(1, this.testSet.size() / 10);
        Set<Candidate> topActualVectors = new HashSet<>();
        for (int i = actualRanked.size() - 1; i > actualRanked.size() - topK; i--) {
            topActualVectors.add(actualRanked.get(i));
        }

        int matches = 0;
        for (int i = predictedRanked.size() - 1; i > predictedRanked.size() - topK; i--) {
            if (topActualVectors.contains(predictedRanked.get(i))) {
                matches++;
            }
        }

        double matchPercentage = ((double) matches / topK) * 100.0;
        LOGGER.info("============ FINAL TEST SET EVALUATION ============");
        LOGGER.info("Test Set Mean Absolute Error (MAE): {}", (totalAbsoluteError / this.testSet.size()));
        LOGGER.info("Top-10% Candidate Ranking Match Accuracy: {}", matchPercentage);
        LOGGER.info("===================================================\n\n");
    }

    private record Candidate(double[] vector, double[] quantiles) implements Comparable<Candidate> {

        @Override
        public boolean equals(Object o) {
            if (o instanceof Candidate(double[] vector1, double[] q1)) {
                return Arrays.equals(vector, vector1);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(vector);
        }

        @Override
        public int compareTo(Candidate o) {
            int p50 = Double.compare(round(this.quantiles[0]), round(o.quantiles[0]));
            if(p50 != 0) {
                return p50;
            }

            double myIqr = round(this.quantiles[3]) - round(this.quantiles[1]);
            double otherIqr = round(o.quantiles[3]) - round(o.quantiles[1]);
            int iqr = Double.compare(otherIqr, myIqr);
            if(iqr != 0) {
                return iqr;
            }

            double myTails = round(this.quantiles[4]) - round(this.quantiles[0]);
            double otherTails = round(o.quantiles[4]) - round(o.quantiles[0]);
            return Double.compare(otherTails, myTails);
        }
    }
}
