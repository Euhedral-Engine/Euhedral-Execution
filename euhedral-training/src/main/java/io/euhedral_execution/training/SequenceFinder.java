package io.euhedral_execution.training;

import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.networks.AbstractNeuralNetwork;
import io.euhedral_execution.training.networks.LeakyReluNetwork;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
        if (args.length > 2) {
            this.learner = new LeakyReluNetwork(args[2]);
        }
        else if(args.length == 2 && args[0].equals("generate")) {
            this.learner = new LeakyReluNetwork(args[1]);
            return;
        } else {
            this.learner = new LeakyReluNetwork(new int[]{28, 128, 128, 1}, 0.001, 0.01, 0.9);
        }

        Path path = Path.of(args[1]);
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            List<String> lines = reader.lines().toList();
            if (lines.isEmpty()) {
                throw new Exception("Empty file");
            }

            int[] choice = new int[10]; // 80% Train
            choice[8] = 1;                             // 10% Validation
            choice[9] = 2;                             // 10% Test
            long seed = ThreadLocalRandom.current().nextLong();

            for (int i = 0; i < lines.size(); i += 2) {
                String[] v = lines.get(i).split("\\s");
                double[] vector = new double[v.length];
                for (int j = 0; j < vector.length; j++) {
                    vector[j] = Double.parseDouble(v[j]);
                }

                String[] q = lines.get(i + 1).split("\\s");
                double p50 = Double.parseDouble(q[2]);

                // Uniform hash bucketing using xxHash64 high bits
                int bucket = choice[(int) Math.unsignedMultiplyHigh(HasherApi.mix(seed++), 10)];
                double[][] dataPair = new double[][]{vector, {p50}};

                // Distribute data points while keeping outliers proportionally split
                if (bucket == 1) {
                    this.validationSet.add(dataPair);
                } else if (bucket == 2) {
                    this.testSet.add(dataPair);
                } else {
                    this.trainingSet.add(dataPair);
                }
            }
        }
    }

    public void generate() throws Exception {
        int kClusters = 28;
        int maxClusterIterations = 500;
        Path historicalData = Paths.get("output/raw_data.txt");

        VectorGrouper grouper = new VectorGrouper(kClusters, maxClusterIterations, historicalData);
        List<VectorGrouper.ClusterScore> rankedClusters = grouper.getClusters();
        double[] bestClusterCentroid = rankedClusters.get(0).cluster.centroid().getPoint();

        SobolSequenceGenerator generator = new SobolSequenceGenerator(28);
        generator.skipTo(1024);

        Path out = Paths.get("output/temp_data");
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }

        int cap = 16_384 - 2048;
        PriorityQueue<Candidate> topCandidates = new PriorityQueue<>(cap + 1,
                Comparator.comparingDouble(a -> a.score[0])
        );

        System.out.println("Screening 2^20 vectors using surrogate model...");
        for (int i = 2048; i < Math.pow(2, 20); i++) {
            double[] vector = generator.get();

            normalize(vector);

            Candidate candidate = new Candidate(vector, this.learner.predict(vector));
            topCandidates.add(candidate);
            if (topCandidates.size() > cap) {
                topCandidates.poll();
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            while (!topCandidates.isEmpty()) {
                String plainVector = toPlainStringSpaceSeparated(topCandidates.poll().vector);
                writer.write(plainVector);
                writer.newLine();
            }

            int pureExplorationCount = 1024;
            int localExploitationCount = 1024;

            System.out.println("Injecting 1,024 global exploration vectors.");
            for (int i = 0; i < pureExplorationCount; i++) {
                double[] vector = generator.get();
                normalize(vector);

                String plainVector = toPlainStringSpaceSeparated(vector);
                writer.write(plainVector);
                writer.newLine();
            }

            System.out.println("Injecting 1,024 cluster-targeted exploitation vectors.");
            Random noiseGenerator = new Random();
            for (int i = 0; i < localExploitationCount; i++) {
                double[] explorationVector = new double[28];
                for (int j = 0; j < 28; j++) {
                    double noise = noiseGenerator.nextGaussian() * 0.05;
                    explorationVector[j] = bestClusterCentroid[j] + noise;
                }
                normalize(explorationVector);

                String plainVector = toPlainStringSpaceSeparated(explorationVector);
                writer.write(plainVector);
                writer.newLine();
            }
        }
        System.out.println("Successfully generated 16,384 vectors for the next active learning execution loop.");
    }


    private static String toPlainStringSpaceSeparated(double[] vector) {
        StringBuilder sb = new StringBuilder(512);
        for (int i = 0; i < vector.length; i++) {
            sb.append(new java.math.BigDecimal(Double.toString(vector[i])).toPlainString());
            if (i < vector.length - 1) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    private static void normalize(double[] vector) {
        for (int d = 0; d < vector.length; d++) {
            vector[d] = -1 + 2 * vector[d];
        }

        int count = 0;
        while (count < vector.length) {
            double squareSum = 0.0;
            for (int i = count; i < count + 7; i++) {
                squareSum += vector[i] * vector[i];
            }

            double length = Math.sqrt(squareSum);
            for (int i = count; i < count + 7; i++) {
                vector[i] /= length;
            }
            count += 7;
        }
    }

    public void train() throws Exception {
        String bestModel = this.learner.export();
        double bestMSE = evaluateSetLoss(this.validationSet);
        int epochsWithoutImprovement = 0;
        int patience = 15;

        System.out.println("Starting training on L2-normalized vector space...");
        for (int i = 0; i < 500; i++) {
            Collections.shuffle(this.trainingSet);

            for (var v : this.trainingSet) {
                this.learner.train(v[0], v[1]);
            }

            double mse = evaluateSetLoss(this.validationSet);

            System.out.printf("Epoch: %d | Train MAE: %.5f | Train MSE: %.5f | Validation MSE: %.5f\n",
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
            double prediction = this.learner.predict(v[0])[0];
            double error = v[1][0] - prediction;
            totalLoss += (error * error);
        }
        return totalLoss / dataset.size();
    }

    private void runFinalEvaluation() {
        double totalAbsoluteError = 0;
        List<DataResult> actualRanked = new ArrayList<>();
        List<DataResult> predictedRanked = new ArrayList<>();

        for (var v : this.testSet) {
            double actual = v[1][0];
            double[] prediction = this.learner.predict(v[0]);
            totalAbsoluteError += Math.abs(actual - prediction[0]);

            actualRanked.add(new DataResult(v[0], v[1]));
            predictedRanked.add(new DataResult(v[0], prediction));
        }

        actualRanked.sort((a, b) -> Double.compare(b.score[0], a.score[0]));
        predictedRanked.sort((a, b) -> Double.compare(b.score[0], a.score[0]));

        // Quantify Top 10% configuration ranking accuracy
        int topK = Math.max(1, this.testSet.size() / 10);
        Set<DataResult> topActualVectors = new HashSet<>();
        for (int i = 0; i < topK; i++) {
            topActualVectors.add(actualRanked.get(i));
        }

        int matches = 0;
        for (int i = 0; i < topK; i++) {
            if (topActualVectors.contains(predictedRanked.get(i))) {
                matches++;
            }
        }

        double matchPercentage = ((double) matches / topK) * 100.0;
        System.out.println("\n============ FINAL TEST SET EVALUATION ============");
        System.out.printf("Test Set Mean Absolute Error (MAE): %.6f total ops/ns\n",
                (totalAbsoluteError / this.testSet.size()));
        System.out.printf("Top-%d Candidate Ranking Match Accuracy: %.2f%%\n", topK,
                matchPercentage);
        System.out.println("===================================================\n\n");
    }

    private record DataResult(double[] vector, double[] score) {
        @Override
        public boolean equals(Object o) {
            if(o instanceof DataResult(double[] vector1, double[] score1)) {
                return Arrays.equals(vector, vector1) && Arrays.equals(score, score1);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(vector) ^ Arrays.hashCode(score);
        }
    }

    private record Candidate(double[] vector, double[] score) implements Comparable<Candidate> {

        @Override
        public int compareTo(Candidate o) {
            return Double.compare(this.score[0], o.score[0]);
        }
    }
}
