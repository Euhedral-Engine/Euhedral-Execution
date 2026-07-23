package io.euhedral_execution.training;

import io.euhedral_execution.core.config.FragmentActionPicker;
import io.euhedral_execution.core.config.FragmentConfig.BenchmarkConfig;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.frames.BenchmarkFrame;
import io.euhedral_execution.core.ingest.ArrayIngestSink;
import io.euhedral_execution.core.utils.FlowDistribution;
import io.euhedral_execution.core.utils.SpinWait;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.training.VectorGrouper.ClusterScore;
import java.io.BufferedReader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.StringJoiner;
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
        if(Objects.equals(args[0], "group")) {
            group();
            return;
        }
        if(Objects.equals(args[0], "train-vector-finder")) {
            SequenceFinder finder = new SequenceFinder(args);
            finder.train();
            return;
        }
        if(Objects.equals(args[0], "generate") && args.length > 1) {
            SequenceFinder finder = new SequenceFinder(args);
            finder.generate();
            return;
        }
        if(!Objects.equals(args[0], "benchmark")) {
            return;
        }

        BenchmarkFrame[][] frames = new BenchmarkFrame[SystemInfo.getCoreCount()][];

        for (int i = 0; i < frames.length; i++) {
            frames[i] = BenchmarkFrame.generate(1_000_000, false,
                    ThreadLocalRandom.current().nextLong());
        }

        VectorProducer generator;
        if(args.length > 1) {
            generator = new VectorProducer(Paths.get(args[1]));
        } else {
            generator = new VectorProducer();
        }
        run(frames, generator);
        printResults();
    }

    private static void run(BenchmarkFrame[][] frames, VectorProducer generator) throws Exception {
        Path output = Path.of("output/raw_data.txt");
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        List<ArrayIngestSink> sinks = new ArrayList<>();
        try (FileWriter writer = new FileWriter(output)) {
            int iteration = 1;
            double[] vector;
            while((vector = generator.get()) != null) {
                DISTRIBUTION = new Distribution(vector);

                System.out.println("Iteration: " + iteration);
                LatticeConfig config = LatticeConfig.benchmarkConfig(
                        new BenchmarkConfig(1_000_000L, EVAL_QUEUE, new FragmentActionPicker(vector),
                                Thread.currentThread()));

                long samples = 0;
                for (int j = 0; j < 5; j++) {
                    ControlPlaneLattice controlPlane = ControlPlaneLattice.getOrCreate(config);
                    controlPlane.start();

                    int workers = controlPlane.getActiveWorkers();
                    for (var f : frames) {
                        ArrayIngestSink sink = new ArrayIngestSink(f);
                        sinks.add(sink);
                        controlPlane.addUpstream(sink);
                    }
                    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
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
                    SpinWait.awaitWhile(() -> EVAL_QUEUE.sizeLong() < workers);
                    addAll();
                    samples++;
                }
                DISTRIBUTION.aggregateMean /= 5;
                for(int i = 0; i < 5; i++) {
                    DISTRIBUTION.quantiles[i] /= samples;
                }
                writer.printlnArraySpaceSeparated(DISTRIBUTION.vector);
                writer.printlnArraySpaceSeparated(DISTRIBUTION.quantiles);
                writer.println(Double.doubleToLongBits(DISTRIBUTION.aggregateMean));
                TOP_SCORES.add(DISTRIBUTION);
                if (TOP_SCORES.size() > 10) {
                    TOP_SCORES.poll();
                }
                iteration++;
            }
        }
    }

    private static void group() throws Exception {
        Path path = Path.of("output/raw_data.txt");
        VectorGrouper grouper = new VectorGrouper(28, 100, path);
        List<ClusterScore> groups = grouper.getClusters();
        for(int i = 0; i < groups.size(); i++) {
            System.out.println((i + 1) + ". " + "Size: " + groups.get(i).cluster.getPoints().size() + " Quantiles: " + groups.get(i) + " Bounds: [" + groups.get(i).min + ", " + groups.get(i).max + "]");
        }
    }

    private static void addAll() {
        double[] totalMean = new double[]{0};
        long count = EVAL_QUEUE.drain(dist -> {
            if (Double.isFinite(dist.p10())) {
                DISTRIBUTION.quantiles[0] += dist.p10();
            }
            if (Double.isFinite(dist.p25())) {
                DISTRIBUTION.quantiles[1] += dist.p25();
            }
            if (Double.isFinite(dist.p50())) {
                DISTRIBUTION.quantiles[2] += dist.p50();
            }
            if (Double.isFinite(dist.p75())) {
                DISTRIBUTION.quantiles[3] += dist.p75();
            }
            if (Double.isFinite(dist.p90())) {
                DISTRIBUTION.quantiles[4] += dist.p90();
            }
            if (Double.isFinite(dist.mean())) {
                totalMean[0] += dist.mean();
            }
        }, Long.MAX_VALUE);
        DISTRIBUTION.aggregateMean += totalMean[0] / Math.max(1, count);
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

        try (FileWriter writer = new FileWriter(path)) {
            writer.println("Top Throughput:");
            for (var d : results) {
                writer.println(String.format("Quantiles: P10: %.8f P25: %.8f P50: %.8f P75: %.8f P90: %.8f AggregateMean: %.8f", d.quantiles[0], d.quantiles[1], d.quantiles[2], d.quantiles[3], d.quantiles[4], d.aggregateMean));
            }
            writer.println("\n\nTop Weights:");
            for (var d : results) {
                writer.println(weightsToCode(d.vector));
            }
            writer.println("\n\nBounds:");
            for (var d : results) {
                double min = Double.MAX_VALUE;
                double max = -min;
                for (double q : d.vector) {
                    min = Math.min(min, q);
                    max = Math.max(max, q);
                }
                writer.println("[" + min + ", " + max + "]");
            }
        }
    }

    private static String arrayToString(double[] arr) {
        StringJoiner sj = new StringJoiner(" ");

        for(double d : arr) {
            sj.add(Long.toString(Double.doubleToLongBits(d)));
        }
        return sj.toString();
    }

    private static String weightsToCode(double[] arr) {
        StringJoiner sj = new StringJoiner(", ");

        for(double d : arr) {
            sj.add(new BigDecimal(d).toPlainString());
        }

        return String.format("double[] weights = new double[]{%s}", sj);
    }

    private static class Distribution implements Comparable<Distribution> {

        static double round(double quantile) {
            return Math.round(quantile * 10_000) / 10_000.0;
        }

        final double[] vector;
        final double[] quantiles = new double[5];
        double aggregateMean = 0;

        Distribution(double[] vector) {
            this.vector = vector;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Distribution other) {
                return Arrays.equals(vector, other.vector) && Arrays.equals(quantiles, other.quantiles);
            }
            return false;
        }


        @Override
        public int compareTo(@NonNull Distribution other) {
            if (this == other) {
                return 0;
            }

            int p50Compare = Double.compare(round(quantiles[2]), round(other.quantiles[2]));
            if (p50Compare != 0) {
                return p50Compare;
            }

            double thisIQR = round(quantiles[3]) - round(quantiles[1]);
            double otherIQR = round(other.quantiles[3]) - round(other.quantiles[1]);
            int iqrCompare = Double.compare(otherIQR, thisIQR);
            if (iqrCompare != 0) {
                return iqrCompare;
            }

            double thisTailRange = round(quantiles[4]) - round(quantiles[0]);
            double otherTailRange = round(other.quantiles[4]) - round(other.quantiles[0]);
            return Double.compare(otherTailRange, thisTailRange);
        }
    }

    private static class VectorProducer implements AutoCloseable {
        final SobolSequenceGenerator generator;
        final BufferedReader reader;

        int count = 0;

        public VectorProducer() {
            this.generator = new SobolSequenceGenerator(28);
            this.generator.skipTo(1024);
            this.reader = null;
        }

        public VectorProducer(Path path) throws  Exception {
            this.generator = null;
            this.reader = Files.newBufferedReader(path);
        }

        double[] get() {
            if(this.generator != null) {
                if(this.count++ >= 16) {
                    return null;
                }
                double[] vector = this.generator.get();
                normalize(vector, -1, 1);
                return vector;
            }
            try {
                String raw = this.reader.readLine();
                if(raw == null) {
                    return null;
                }
                String[] tokens = raw.split("\\s+");
                double[] vector = new double[tokens.length];
                for(int i = 0; i < tokens.length; i++) {
                    vector[i] = Double.longBitsToDouble(Long.parseLong(tokens[i]));
                }
                return vector;
            } catch (Exception e) {
                return null;
            }
        }

        private static void normalize(double[] vector, double min, double max) {
            for (int d = 0; d < vector.length; d++) {
                vector[d] = min + (max - min) * vector[d];
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

        @Override
        public void close() throws Exception {
            if(reader != null) {
                reader.close();
            }
        }
    }
}
