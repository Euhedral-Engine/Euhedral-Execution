package io.euhedral_execution.training;

import io.euhedral_execution.core.config.FragmentActionPicker;
import io.euhedral_execution.core.config.FragmentConfig.BenchmarkConfig;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.frames.BenchmarkFrame;
import io.euhedral_execution.core.ingest.ArrayIngestSink;
import io.euhedral_execution.core.utils.FlowDistribution;
import io.euhedral_execution.core.utils.P2Quantile;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import io.euhedral_execution.hardware_utils.SystemInfo;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.apache.commons.math4.legacy.random.SobolSequenceGenerator;
import org.jspecify.annotations.NonNull;

public class Runner {

    private static final PriorityQueue<Distribution> TOP_SCORES = new PriorityQueue<>(11);
    private static final MpscQueue<FlowDistribution> EVAL_QUEUE = new MpscQueue<>(
            SystemInfo.getCoreCount() * 2);

    private static Distribution DISTRIBUTION;

    public static void main(String[] args) throws Exception {
        Path path = Path.of("output/raw_data.txt");
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, "", StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        BenchmarkFrame[][] frames = new BenchmarkFrame[SystemInfo.getCoreCount()][];

        for (int i = 0; i < frames.length; i++) {
            frames[i] = BenchmarkFrame.generate(1_000_000, false,
                    ThreadLocalRandom.current().nextLong());
        }

        int features = 6;
        int bias = 1;
        int actions = 4;
        SobolSequenceGenerator generator = new SobolSequenceGenerator((features + bias) * actions);
        generator.skipTo(1024);

//        LEARNER = new ActionLearner(new int[]{(features + bias) * actions, 64, 64}, 0.001, 0.01, 0.9);
        List<ArrayIngestSink> sinks = new ArrayList<>();
        try (BufferedWriter writer = Files.newBufferedWriter(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            for (int i = 16_384; i > 0; i--) {
                double[] vector = generator.get();
                normalize(vector, -1, 1, features + bias);

                DISTRIBUTION = new Distribution(vector);

                System.out.println("Countdown: " + i);
                int iterations = 0;
                LatticeConfig config = LatticeConfig.benchmarkConfig(
                        new BenchmarkConfig(1_000_000L, EVAL_QUEUE, new FragmentActionPicker(vector),
                                Thread.currentThread()));

                for (int j = 0; j < 5; j++) {
                    ControlPlaneLattice controlPlane = ControlPlaneLattice.getOrCreate(config);
                    controlPlane.start();

                    int workers = controlPlane.getActiveWorkers();
                    for (var f : frames) {
                        ArrayIngestSink sink = new ArrayIngestSink(f);
                        sinks.add(sink);
                        controlPlane.addUpstream(sink);
                    }
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
                    long now = 0;
                    while (now < deadline) {
                        now = System.nanoTime();
                        LockSupport.parkNanos(deadline - now);
                        sinks.removeIf(ArrayIngestSink::isComplete);
                        if (EVAL_QUEUE.sizeLong() == workers || sinks.isEmpty()) {
                            break;
                        }
                    }
                    controlPlane.close();
                    for (var sink : sinks) {
                        sink.complete();
                    }
                    sinks.clear();
                    while (EVAL_QUEUE.sizeLong() < workers) {
                        Thread.yield();
                    }
                    addAll();
                    iterations++;
                }
                if (iterations == 5) {
                    writer.append(Arrays.toString(vector).replace("[", "").replace("]", "").replace(",", ""));
                    writer.newLine();
                    writer.append(String.format("%f %f %f %f %f", DISTRIBUTION.P10.value(), DISTRIBUTION.P25.value(), DISTRIBUTION.P50.value(), DISTRIBUTION.P75.value(), DISTRIBUTION.P90.value()));
                    writer.newLine();
                    TOP_SCORES.add(DISTRIBUTION);
                }
                if (TOP_SCORES.size() > 10) {
                    TOP_SCORES.poll();
                }
            }
        }
        printResults();
    }

    private static void normalize(double[] vector, double min, double max, int groupSize) {
        for (int d = 0; d < vector.length; d++) {
            vector[d] = min + (max - min) * vector[d];
        }

        int count = 0;
        while (count < vector.length) {
            double squareSum = 0.0;
            for (int i = count; i < count + groupSize; i++) {
                squareSum += vector[i] * vector[i];
            }

            double length = Math.sqrt(squareSum);
            for (int i = count; i < count + groupSize; i++) {
                vector[i] /= length;
            }
            count += groupSize;
        }
    }

    private static void addAll() {
        double[] quantiles = new double[5];
        EVAL_QUEUE.drain(dist -> {
            if (Double.isFinite(dist.p10())) {
                quantiles[0] += dist.p10();
            }
            if (Double.isFinite(dist.p25())) {
                quantiles[1] += dist.p25();
            }
            if (Double.isFinite(dist.p50())) {
                quantiles[2] += dist.p50();
            }
            if (Double.isFinite(dist.p75())) {
                quantiles[3] += dist.p75();
            }
            if (Double.isFinite(dist.p90())) {
                quantiles[4] += dist.p90();
            }
        }, Long.MAX_VALUE);
        DISTRIBUTION.P10.add(quantiles[0]);
        DISTRIBUTION.P25.add(quantiles[1]);
        DISTRIBUTION.P50.add(quantiles[2]);
        DISTRIBUTION.P75.add(quantiles[3]);
        DISTRIBUTION.P90.add(quantiles[4]);
    }

    private static void printResults() throws Exception {
        Path path = Path.of("output/results.txt");
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, "", StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        List<Distribution> results = new ArrayList<>();
        while (!TOP_SCORES.isEmpty()) {
            results.add(TOP_SCORES.poll());
        }

        try (BufferedWriter writer = Files.newBufferedWriter(path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            writer.append("Top Throughput:\n");
            for (var d : results) {
                writer.append(d.P50.value() + "\n");
            }
            writer.append("\n\nTop Weights:\n");
            for (var d : results) {
                StringBuilder sb = new StringBuilder();
                sb.append("double[] weights = new double");
                sb.append(Arrays.toString(d.vector).replace("[", "{").replace("]", "}"));
                sb.append("\n");
                writer.append(sb);
            }
            writer.append("\n\nBounds:\n");
            for (var d : results) {
                double min = Double.MAX_VALUE;
                double max = -min;
                for (double q : d.vector) {
                    min = Math.min(min, q);
                    max = Math.max(max, q);
                }
                writer.append("[" + min + ", " + max + "]\n");
            }
        }
    }

    private static class Distribution implements Comparable<Distribution> {

        static double round(P2Quantile quantile) {
            return Math.round(quantile.value() * 10_000) / 10_000.0;
        }

        final double[] vector;
        private final P2Quantile P10 = new P2Quantile(0.5);
        private final P2Quantile P25 = new P2Quantile(0.5);
        private final P2Quantile P50 = new P2Quantile(0.5);
        private final P2Quantile P75 = new P2Quantile(0.5);
        private final P2Quantile P90 = new P2Quantile(0.5);

        Distribution(double[] vector) {
            this.vector = vector;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Distribution other) {
                return P10.value() == other.P10.value() &&
                        P25.value() == other.P25.value() &&
                        P50.value() == other.P50.value() &&
                        P75.value() == other.P75.value() &&
                        P90.value() == other.P90.value();
            }
            return false;
        }


        @Override
        public int compareTo(@NonNull Distribution other) {
            if (this == other) {
                return 0;
            }

            int p50Compare = Double.compare(round(this.P50), round(other.P50));
            if (p50Compare != 0) {
                return p50Compare;
            }

            double thisIQR = round(this.P75) - round(this.P25);
            double otherIQR = round(other.P75) - round(other.P25);
            int iqrCompare = Double.compare(otherIQR, thisIQR);
            if (iqrCompare != 0) {
                return iqrCompare;
            }

            double thisTailRange = round(this.P90) - round(this.P10);
            double otherTailRange = round(other.P90) - round(other.P10);
            return Double.compare(otherTailRange, thisTailRange);
        }

        public double score() {
            return P50.value() - (P75.value() - P25.value());
        }
    }
}
