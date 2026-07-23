package io.euhedral_execution.training;

import io.euhedral_execution.core.utils.FlowDistribution;
import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;
import lombok.Getter;
import org.apache.commons.math4.legacy.ml.clustering.CentroidCluster;
import org.apache.commons.math4.legacy.ml.clustering.Clusterable;
import org.apache.commons.math4.legacy.ml.clustering.KMeansPlusPlusClusterer;
import org.jspecify.annotations.NonNull;

public class VectorGrouper {

    @Getter
    private final List<ClusterScore> clusters;

    public VectorGrouper(int k, int maxIterations, Path file) throws Exception {
        KMeansPlusPlusClusterer<Node> clusterer = new KMeansPlusPlusClusterer<>(k, maxIterations);
        List<Node> vectors = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            List<String> lines = reader.lines().toList();
            if (lines.isEmpty()) {
                throw new Exception("Empty file");
            }

            for (int i = 0; i < lines.size(); i += 2) {
                String[] v = lines.get(i).split("\\s");
                double[] vector = new double[v.length];
                for (int j = 0; j < vector.length; j++) {
                    vector[j] = Double.parseDouble(v[j]);
                }

                String[] q = lines.get(i + 1).split("\\s");
                double[] quantiles = new double[5];
                for (int j = 0; j < 5; j++) {
                    quantiles[j] = Double.parseDouble(q[j]);
                }
                vectors.add(new Node(vector, quantiles));
            }
        }
        this.clusters = rankAndSort(clusterer.cluster(vectors));
    }

    private List<ClusterScore> rankAndSort(List<CentroidCluster<Node>> clusters) {
        List<ClusterScore> scores = new ArrayList<>();
        for(var cluster : clusters) {
            List<Node> points = cluster.getPoints();
            if(points.isEmpty() || points.size() < 5) {
                continue;
            }
            FlowDistribution distribution = new FlowDistribution();
            for(Node node : points) {
                double p75 = node.quantiles[3];
                distribution.record(p75);
            }
            ClusterScore score = new ClusterScore(distribution, cluster);
            scores.add(score);
        }
        Collections.sort(scores);
        return scores;
    }

    private static class Node implements Clusterable {

        private final double[] vector;
        private final double[] quantiles;

        public Node(double[] vector, double[] quantiles) {
            this.vector = vector;
            this.quantiles = quantiles;
        }

        @Override
        public double[] getPoint() {
            return vector;
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof Node)) {
                return false;
            }
            return Arrays.equals(vector, ((Node) other).vector);
        }
    }

    public static class ClusterScore implements Comparable<ClusterScore> {
        static double round(double quantile) {
            return Math.round(quantile * 10_000) / 10_000.0;
        }

        final FlowDistribution distribution;
        public final CentroidCluster<Node> cluster;
        public final double min;
        public final double max;

        public ClusterScore(FlowDistribution distribution, CentroidCluster<Node> cluster) {
            this.distribution = distribution;
            this.cluster = cluster;

            double min = Double.MAX_VALUE, max = - Double.MAX_VALUE;
            for(Node n : cluster.getPoints()) {
                for(double p : n.vector) {
                    min = Math.min(min, p);
                    max = Math.max(max, p);
                }
            }
            this.min = min;
            this.max = max;
        }

        @Override
        public int compareTo(@NonNull ClusterScore other) {
            return -Double.compare(round(distribution.p50()), round(other.distribution.p50()));
        }

        @Override
        public String toString() {
            StringJoiner sb = new StringJoiner(", ");
            sb.add(distribution.p10() + "");
            sb.add(distribution.p25() + "");
            sb.add(distribution.p50() + "");
            sb.add(distribution.p75() + "");
            sb.add(distribution.p90() + "");
            return sb.toString();
        }
    }
}
