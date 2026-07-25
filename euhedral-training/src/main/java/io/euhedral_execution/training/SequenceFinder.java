package io.euhedral_execution.training;

import static io.euhedral_execution.training.utils.CommonFunctions.round;

import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.networks.AbstractNeuralNetwork;
import io.euhedral_execution.training.networks.LeakyReluNetwork;
import io.euhedral_execution.training.utils.BenchmarkOutputReader;
import io.euhedral_execution.training.utils.BenchmarkOutputWriter;
import io.euhedral_execution.training.utils.CommonFunctions;
import io.euhedral_execution.training.utils.VectorGrouper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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

public class SequenceFinder {

    private final AbstractNeuralNetwork learner;
    private final List<double[][]> trainingSet = new ArrayList<>();
    private final List<double[][]> validationSet = new ArrayList<>();
    private final List<double[][]> testSet = new ArrayList<>();

    public SequenceFinder(String[] args) throws Exception {
        boolean gen = System.getProperty("generate") != null;
        String loadModel = System.getProperty("model");

        if (loadModel != null && !loadModel.isBlank()) {
            this.learner = new LeakyReluNetwork(loadModel);
        } else {
            this.learner = new LeakyReluNetwork(new int[]{28, 128, 128, 64, 5}, 0.001, 0.01, 0.1);
        }
        if(gen) {
            generate();
            return;
        }

        Path path = Path.of(args[1]);
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
        generator.skipTo(1024);

        Path out = Paths.get("output/temp_data");
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }

        int cap = 32_768 - 4096;
        PriorityQueue<Candidate> topCandidates = new PriorityQueue<>(cap + 1);

        System.out.println("Screening vectors...");
        for (int i = 4096; i < Math.pow(2, 21); i++) {
            double[] vector = generator.get();

            CommonFunctions.normalizeSobolVector(vector);

            Candidate candidate = new Candidate(vector, this.learner.predict(vector));
            topCandidates.add(candidate);
            if (topCandidates.size() > cap) {
                topCandidates.poll();
            }
        }

        try (BenchmarkOutputWriter writer = new BenchmarkOutputWriter(out)) {

            while (!topCandidates.isEmpty()) {
                writer.spaceSeparatedWriteLine(topCandidates.poll().vector);
            }

            int pureExplorationCount = 2048;
            int localExploitationCount = 2048;

            System.out.println("Injecting global exploration vectors.");
            for (int i = 0; i < pureExplorationCount; i++) {
                double[] vector = generator.get();
                CommonFunctions.normalizeSobolVector(vector);
                writer.spaceSeparatedWriteLine(vector);
            }

            System.out.println("Injecting cluster-targeted exploitation vectors.");
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
        System.out.println(
                "Successfully generated 32,768 vectors for the next active learning execution loop.");
    }

    public void train() throws Exception {
        String bestModel = this.learner.export();
        double bestMSE = evaluateSetLoss(this.validationSet);
        int epochsWithoutImprovement = 0;
        int patience = 15;

        System.out.println("Starting training on normalized vector space...");
        for (int i = 0; i < 500; i++) {
            Collections.shuffle(this.trainingSet);

            for (var v : this.trainingSet) {
                this.learner.train(v[0], v[1]);
            }

            double mse = evaluateSetLoss(this.validationSet);

            System.out.printf(
                    "Epoch: %d | Train MAE: %.5f | Train MSE: %.5f | Validation MSE: %.5f\n",
                    i, this.learner.getMAE(), this.learner.getMSE(), mse);

            if (mse < bestMSE) {
                bestMSE = mse;
                epochsWithoutImprovement = 0;
                bestModel = this.learner.export();
            } else {
                epochsWithoutImprovement++;
                if (epochsWithoutImprovement >= patience) {
                    System.out.printf(
                            "Early stopping triggered at Epoch %d. Best Validation Loss: %.5f\n", i,
                            bestMSE);
                    break;
                }
            }
        }

        runFinalEvaluation();
        Path modelPath = Paths.get("output/model/latest");
        if (modelPath.getParent() != null) {
            Files.createDirectories(modelPath.getParent());
        }
        Files.writeString(modelPath, bestModel, StandardOpenOption.CREATE,
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
        System.out.println("\n============ FINAL TEST SET EVALUATION ============");
        System.out.printf("Test Set Mean Absolute Error (MAE): %.6f%%\n",
                (totalAbsoluteError / this.testSet.size()));
        System.out.printf("Top-%d Candidate Ranking Match Accuracy: %.2f%%\n", topK,
                matchPercentage);
        System.out.println("===================================================\n\n");
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
