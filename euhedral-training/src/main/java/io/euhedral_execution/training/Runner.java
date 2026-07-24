package io.euhedral_execution.training;

import com.tdunning.math.stats.MergingDigest;
import com.tdunning.math.stats.TDigest;
import io.euhedral_execution.core.config.FragmentActionPicker;
import io.euhedral_execution.core.config.FragmentConfig.BenchmarkConfig;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.frames.BenchmarkFrame;
import io.euhedral_execution.core.utils.FlowDistribution;
import io.euhedral_execution.core.utils.SpinWait;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
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
        if (Objects.equals(args[0], "group")) {
            group();
            return;
        }
        if (Objects.equals(args[0], "train-vector-finder")) {
            SequenceFinder finder = new SequenceFinder(args);
            finder.train();
            return;
        }
        if (Objects.equals(args[0], "generate") && args.length > 1) {
            SequenceFinder finder = new SequenceFinder(args);
            finder.generate();
            return;
        }
        if (!Objects.equals(args[0], "benchmark")) {
            return;
        }

        BenchmarkFrame[][] frames = new BenchmarkFrame[SystemInfo.getCoreCount()][];

        for (int i = 0; i < frames.length; i++) {
            frames[i] = BenchmarkFrame.generate(1_000_000, false,
                    ThreadLocalRandom.current().nextLong());
        }

        VectorProducer generator;
        if (args.length > 1) {
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
        ThreadTools.setAffinity(SystemInfo.getCoreInfo(0).getCpuSet().nextSetBit(0));

        try (BenchmarkOutputWriter writer = new BenchmarkOutputWriter(output)) {
            double[] vector;

            double[] halt = new double[28];
            FragmentActionPicker actionPicker = new FragmentActionPicker(halt);
            LatticeConfig config = LatticeConfig.benchmarkConfig(
                    new BenchmarkConfig(1_000_000L, EVAL_QUEUE,
                            actionPicker, Thread.currentThread()));

            ControlPlaneLattice controlPlane = ControlPlaneLattice.getOrCreate(config);
            controlPlane.start();

            List<BenchmarkSink> sinks = new ArrayList<>();
            for (var f : frames) {
                BenchmarkSink sink = new BenchmarkSink(f);
                sinks.add(sink);
                controlPlane.addUpstream(sink);
            }

            int index = 1;
            double[] means = new double[10];
            while ((vector = generator.get()) != null) {
                DISTRIBUTION = new Distribution(vector);
                Arrays.fill(means, 0);

                System.out.println("Vector: " + index++);

                double[] state = new double[]{0, 0, 0};
                actionPicker.setWeights(vector);
                for (int j = 0; j < 10; j++) {
                    for (var sink : sinks) {
                        sink.resetCounter();
                    }
                    long start = System.nanoTime();
                    state[1] = start + 50_000_000;
                    long runTime = start + 200_000_000;

                    while (true) {
                        LockSupport.parkNanos(50_000_000);
                        long now = System.nanoTime();
                        long current = 0;
                        for (var sink : sinks) {
                            current += sink.getConsumed();
                        }
                        if (now >= runTime) {
                            state[0] = current;
                            break;
                        }
                        if (current == state[0] && now > state[1]) {
                            state[2] = 1;
                            break;
                        }
                        state[0] = current;
                        state[1] = now + TimeUnit.MILLISECONDS.toNanos(50);
                    }
                    double throughput = state[0] / (System.nanoTime() - start);
                    means[j] = throughput;
                    DISTRIBUTION.digest.add(throughput);
                    DISTRIBUTION.mean += (throughput - DISTRIBUTION.mean) / (j + 1);
                    if (state[2] > 0) {
                        break;
                    }
                }
                actionPicker.setWeights(halt);

                writer.spaceSeparatedWriteLine(DISTRIBUTION.vector);
                writer.spaceSeparatedWriteLine(means);
                TOP_SCORES.add(DISTRIBUTION);
                if (TOP_SCORES.size() > 10) {
                    TOP_SCORES.poll();
                }

                SpinWait.awaitWhile(() -> {
                    long count = 0;
                    for (var sink : sinks) {
                        count += sink.getConsumed();
                        sink.resetCounter();
                    }
                    return count > 0;
                });
            }
            controlPlane.close();
        }
    }

    private static void group() throws Exception {
        Path path = Path.of("output/raw_data.txt");
        VectorGrouper grouper = new VectorGrouper(28, 100, path);
        List<ClusterScore> groups = grouper.getClusters();
        for (int i = 0; i < groups.size(); i++) {
            System.out.println((i + 1) + ". " + "Size: " + groups.get(i).cluster.getPoints().size()
                    + " Quantiles: " + groups.get(i) + " Bounds: [" + groups.get(i).min + ", "
                    + groups.get(i).max + "]");
        }
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

        try (BenchmarkOutputWriter writer = new BenchmarkOutputWriter(path)) {
            writer.writeLine("Top Throughput:");
            for (var d : results) {
                writer.writeLine(String.format(
                        "Quantiles: P10: %.8f P25: %.8f P50: %.8f P75: %.8f P90: %.8f AggregateMean: %.8f",
                        d.digest.quantile(0.1), d.digest.quantile(0.25), d.digest.quantile(0.5),
                        d.digest.quantile(0.75), d.digest.quantile(0.9), d.mean));
            }
            writer.writeLine("\n\nTop Weights:");
            for (var d : results) {
                writer.writeLine(weightsToCode(d.vector));
            }
            writer.writeLine("\n\nBounds:");
            for (var d : results) {
                double min = Double.MAX_VALUE;
                double max = -min;
                for (double q : d.vector) {
                    min = Math.min(min, q);
                    max = Math.max(max, q);
                }
                writer.writeLine("[" + min + ", " + max + "]");
            }
        }
    }

    private static String weightsToCode(double[] arr) {
        StringJoiner sj = new StringJoiner(", ");

        for (double d : arr) {
            sj.add(new BigDecimal(d).toPlainString());
        }

        return String.format("double[] weights = new double[]{%s}", sj);
    }

    private static class Distribution implements Comparable<Distribution> {

        static double round(double quantile) {
            return Math.round(quantile * 10_000) / 10_000.0;
        }

        final double[] vector;

        TDigest digest = new MergingDigest(100);
        double mean = 0;

        Distribution(double[] vector) {
            this.vector = vector;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Distribution other) {
                return Arrays.equals(vector, other.vector);
            }
            return false;
        }


        @Override
        public int compareTo(@NonNull Distribution other) {
            if (this == other) {
                return 0;
            }

            int p50Compare = Double.compare(round(digest.quantile(0.5)),
                    round(other.digest.quantile(0.5)));
            if (p50Compare != 0) {
                return p50Compare;
            }

            double thisIQR = round(digest.quantile(0.75)) - round(digest.quantile(0.25));
            double otherIQR =
                    round(other.digest.quantile(0.75)) - round(other.digest.quantile(0.25));
            int iqrCompare = Double.compare(otherIQR, thisIQR);
            if (iqrCompare != 0) {
                return iqrCompare;
            }

            double thisTailRange = round(digest.quantile(0.9)) - round(digest.quantile(0.1));
            double otherTailRange =
                    round(other.digest.quantile(0.9)) - round(other.digest.quantile(0.1));
            return Double.compare(otherTailRange, thisTailRange);
        }

        double[] toArray() {
            if (!Double.isFinite(digest.quantile(.5))) {
                for (int i = 0; i < 100; i++) {
                    digest.add(0);
                }
            }
            return new double[]{digest.quantile(0.1), digest.quantile(0.25), digest.quantile(0.5),
                    digest.quantile(0.75), digest.quantile(0.9), mean};
        }
    }

    private static class VectorProducer implements AutoCloseable {

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

        final SobolSequenceGenerator generator;
        final BufferedReader reader;
        int count = 0;

        public VectorProducer() {
            this.generator = new SobolSequenceGenerator(28);
            this.generator.skipTo(1024);
            this.reader = null;
        }

        public VectorProducer(Path path) throws Exception {
            this.generator = null;
            this.reader = Files.newBufferedReader(path);
        }

        double[] get() {
            if (this.generator != null) {
                if (this.count++ >= 16_384) {
                    return null;
                }
                double[] vector = this.generator.get();
                normalize(vector, -1, 1);
                return vector;
            }
            try {
                String raw = this.reader.readLine();
                if (raw == null) {
                    return null;
                }
                String[] tokens = raw.split("\\s+");
                double[] vector = new double[tokens.length];
                for (int i = 0; i < tokens.length; i++) {
                    vector[i] = Double.longBitsToDouble(Long.parseLong(tokens[i]));
                }
                return vector;
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void close() throws Exception {
            if (reader != null) {
                reader.close();
            }
        }
    }
}
