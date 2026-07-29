package io.euhedral_execution.training.utils;

import static io.euhedral_execution.training.utils.CommonFunctions.round;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.tdunning.math.stats.MergingDigest;
import com.tdunning.math.stats.TDigest;
import lombok.Getter;
import org.apache.commons.math4.legacy.ml.clustering.CentroidCluster;
import org.apache.commons.math4.legacy.ml.clustering.Clusterable;
import org.apache.commons.math4.legacy.ml.clustering.KMeansPlusPlusClusterer;

public class VectorGrouper {

    @Getter
    private final List<ClusterScore> clusters;

    public VectorGrouper(int k, int maxIterations, Path file) throws Exception {
        KMeansPlusPlusClusterer<Node> clusterer = new KMeansPlusPlusClusterer<>(k, maxIterations);
        List<Node> vectors = new ArrayList<>();

        try (BenchmarkOutputReader reader = new BenchmarkOutputReader(file)) {
            while (true) {
                double[] vector = reader.readDoubleArray();
                if (vector == null) {
                    break;
                }

                double[] quantiles = reader.readDoubleArray();
                vectors.add(new Node(vector, quantiles));
            }
        }
        this.clusters = rankAndSort(clusterer.cluster(vectors));
    }

    private List<ClusterScore> rankAndSort(List<CentroidCluster<Node>> clusters) {
        List<ClusterScore> scores = new ArrayList<>();
        for (var cluster : clusters) {
            List<Node> points = cluster.getPoints();
            if (points.size() < 5) {
                continue;
            }

            ClusterScore score = new ClusterScore(cluster);
            for (Node node : points) {
                // Rank policy families by the distribution of their observed median throughput.
                score.digest.add(node.quantiles[2]);
            }
            scores.add(score);
        }
        Collections.sort(scores);
        return scores;
    }

    public record Node(double[] vector, double[] quantiles)
            implements Clusterable, Comparable<Node> {

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

        @Override
        public int hashCode() {
            return Arrays.hashCode(vector);
        }

        @Override
        public int compareTo(Node o) {
            int p50 = Double.compare(round(this.quantiles[2]), round(o.quantiles[2]));
            if (p50 != 0) {
                return p50;
            }

            double myIqr = round(this.quantiles[3]) - round(this.quantiles[1]);
            double otherIqr = round(o.quantiles[3]) - round(o.quantiles[1]);
            int iqr = Double.compare(otherIqr, myIqr);
            if (iqr != 0) {
                return iqr;
            }

            double myTails = round(this.quantiles[4]) - round(this.quantiles[0]);
            double otherTails = round(o.quantiles[4]) - round(o.quantiles[0]);
            return Double.compare(otherTails, myTails);
        }
    }

    public static class ClusterScore implements Comparable<ClusterScore> {

        public final CentroidCluster<Node> cluster;
        final TDigest digest = new MergingDigest(4_096);

        public ClusterScore(CentroidCluster<Node> cluster) {
            this.cluster = cluster;
        }

        @Override
        public int compareTo(ClusterScore o) {
            double myP50 = round(this.digest.quantile(0.5));
            double otherP50 = round(o.digest.quantile(0.5));

            if (!Double.isFinite(myP50)) {
                return -1;
            }
            if (!Double.isFinite(otherP50)) {
                return 1;
            }

            int comp = Double.compare(myP50, otherP50);
            if (comp != 0) {
                return comp;
            }

            double myIqr = round(this.digest.quantile(0.75) - this.digest.quantile(0.25));
            double otherIqr = round(o.digest.quantile(0.75) - o.digest.quantile(0.25));

            comp = Double.compare(otherIqr, myIqr);
            if (comp != 0) {
                return comp;
            }

            double myTails = round(this.digest.quantile(0.9) - this.digest.quantile(0.1));
            double otherTails = round(o.digest.quantile(0.9) - o.digest.quantile(0.1));
            return Double.compare(otherTails, myTails);
        }
    }
}
