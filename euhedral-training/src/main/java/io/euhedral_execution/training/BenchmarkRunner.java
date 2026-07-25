package io.euhedral_execution.training;

import io.euhedral_execution.core.config.FragmentActionPicker;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.control_plane.ControlPlaneShard;
import io.euhedral_execution.core.frames.BenchmarkFrame;
import io.euhedral_execution.core.impl.BaseCloneableObject;
import io.euhedral_execution.core.utils.SpinWait;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.training.utils.BenchmarkFrameSink;
import io.euhedral_execution.training.utils.BenchmarkOutputReader;
import io.euhedral_execution.training.utils.BenchmarkOutputWriter;
import io.euhedral_execution.training.utils.CommonFunctions;
import io.euhedral_execution.training.utils.Distribution;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.apache.commons.math4.legacy.random.SobolSequenceGenerator;

public class BenchmarkRunner {
    private static final PriorityQueue<Distribution> TOP_SCORES = new PriorityQueue<>(11);

    public static void run(String[] args) throws Exception {
        BenchmarkFrame[][] frames = new BenchmarkFrame[SystemInfo.getCoreCount()][];

        for (int i = 0; i < frames.length; i++) {
            frames[i] = BenchmarkFrame.generate(1_000_000, false,
                    ThreadLocalRandom.current().nextLong());
        }

        VectorProducer generator;
        if (args.length > 1) {
            generator = new VectorProducer(Paths.get(args[1]));
        } else {
            String limitString = System.getProperty("limit");
            int limit = 16_384;
            if(limitString != null && !limitString.isBlank()) {
                limit = Integer.parseInt(limitString);
            }
            generator = new VectorProducer(limit);
        }
        run(frames, generator);
        printResults();
    }

    private static void run(BenchmarkFrame[][] frames, VectorProducer generator) throws Exception {
        Path output = Path.of("output/benchmark/raw_data.txt");
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        ThreadTools.setAffinity(SystemInfo.getCoreInfo(0).getCpuSet().nextSetBit(0));

        try (BenchmarkOutputWriter writer = new BenchmarkOutputWriter(output)) {
            double[] vector;

            double[] halt = new double[28];
            FragmentActionPicker actionPicker = new FragmentActionPicker(halt);
            LatticeConfig config = new LatticeConfig("Benchmark", SystemInfo.getCpuSet(),
                    Duration.ofSeconds(1), ControlPlaneShard.createBaseShard("Shard", new BaseCloneableObject(
                    FragmentConfig.ofBenchmark(actionPicker))));

            ControlPlaneLattice controlPlane = ControlPlaneLattice.getOrCreate(config);
            controlPlane.start();

            List<BenchmarkFrameSink> sinks = new ArrayList<>();
            for (var f : frames) {
                BenchmarkFrameSink sink = new BenchmarkFrameSink(f);
                sinks.add(sink);
                controlPlane.addUpstream(sink);
            }

            int index = 1;
            double[] means = new double[10];
            while ((vector = generator.get()) != null) {
                Distribution distribution = new Distribution(vector);
                Arrays.fill(means, 0);

                if(generator.limit == Integer.MAX_VALUE) {
                    System.out.println("Vector: " + index++);
                } else {
                    System.out.printf("Vector: (%d / %d)%n", index++, generator.limit);
                }

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
                    distribution.digest.add(throughput);
                    distribution.mean += (throughput - distribution.mean) / (j + 1);
                    if (state[2] > 0) {
                        break;
                    }
                }
                actionPicker.setWeights(halt);

                writer.spaceSeparatedWriteLine(distribution.vector);
                writer.spaceSeparatedWriteLine(means);
                TOP_SCORES.add(distribution);
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

    private BenchmarkRunner() {

    }

    private static class VectorProducer implements AutoCloseable {

        final SobolSequenceGenerator generator;
        final BenchmarkOutputReader reader;
        final int limit;

        int count = 0;

        public VectorProducer(int limit) {
            this.limit = limit;
            this.generator = new SobolSequenceGenerator(28);
            this.generator.skipTo(1024);
            this.reader = null;
        }

        public VectorProducer(Path path) throws Exception {
            this.limit = Integer.MAX_VALUE;
            this.generator = null;
            this.reader = new BenchmarkOutputReader(path);
        }

        double[] get() {
            if (this.generator != null) {
                if (this.count++ >= this.limit) {
                    return null;
                }
                double[] vector = this.generator.get();
                CommonFunctions.normalizeSobolVector(vector);
                return vector;
            }
            try {
                return this.reader.readDoubleArray();
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void close() throws Exception {
            if (this.reader != null) {
                this.reader.close();
            }
        }
    }
}
