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
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.apache.commons.math4.legacy.random.SobolSequenceGenerator;

public class BenchmarkRunner {

    private static final PriorityQueue<Distribution> TOP_SCORES = new PriorityQueue<>(11);

    private static BenchmarkFrame[][] generateFrames() {
        String sourceRatio = System.getProperty("sourceRatio");

        int sources = SystemInfo.getCoreCount();
        if (sourceRatio != null && !sourceRatio.isBlank()) {
            double ratio = Double.parseDouble(sourceRatio);
            sources = (int) Math.round(SystemInfo.getCoreCount() * ratio);
            sources = Math.max(sources, 1);
        }

        int framesPerSource = Integer.getInteger("benchmark.framesPerSource", 100_000);
        BenchmarkFrame[][] frames = new BenchmarkFrame[sources][];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = BenchmarkFrame.generate(framesPerSource, false,
                    ThreadLocalRandom.current().nextLong());
        }
        return frames;
    }

    public static Path run(String[] args) throws Exception {
        Path rawOutput = Path.of(System.getProperty("benchmark.output",
                "output/benchmark/raw_data.txt"));
        Path resultsOutput = Path.of(System.getProperty("benchmark.results", "output/results.txt"));

        if (args.length > 1) {
            return run(Paths.get(args[1]), rawOutput, resultsOutput);
        }

        int limit = Integer.getInteger("limit", 16_384);
        return run(limit, rawOutput, resultsOutput);
    }

    public static Path run(Path candidates, Path rawOutput, Path resultsOutput) throws Exception {
        try (VectorProducer producer = new VectorProducer(candidates)) {
            return run(generateFrames(), producer, rawOutput, resultsOutput);
        }
    }

    public static Path run(int sobolCount, Path rawOutput, Path resultsOutput) throws Exception {
        try (VectorProducer producer = new VectorProducer(sobolCount)) {
            return run(generateFrames(), producer, rawOutput, resultsOutput);
        }
    }

    private static Path run(BenchmarkFrame[][] frames, VectorProducer generator, Path rawOutput,
            Path resultsOutput) throws Exception {
        if (rawOutput.getParent() != null) {
            Files.createDirectories(rawOutput.getParent());
        }
        TOP_SCORES.clear();
        ThreadTools.setAffinity(SystemInfo.getCoreInfo(0).getCpuSet().nextSetBit(0));

        double[] halt = new double[28];
        FragmentActionPicker actionPicker = new FragmentActionPicker(halt);
        LatticeConfig config = new LatticeConfig("Benchmark", SystemInfo.getCpuSet(),
                Duration.ofSeconds(1), ControlPlaneShard.createBaseShard("Shard",
                new BaseCloneableObject(FragmentConfig.ofBenchmark(actionPicker))));
        ControlPlaneLattice controlPlane = ControlPlaneLattice.getOrCreate(config);

        try (BenchmarkOutputWriter writer = new BenchmarkOutputWriter(rawOutput)) {
            controlPlane.start();

            List<BenchmarkFrameSink> sinks = new ArrayList<>();
            for (var frameSet : frames) {
                BenchmarkFrameSink sink = new BenchmarkFrameSink(frameSet);
                sinks.add(sink);
                controlPlane.addUpstream(sink);
            }

            int index = 1;
            int repetitions = Integer.getInteger("benchmark.repetitions", 10);
            long sampleNanos = TimeUnit.MILLISECONDS.toNanos(
                    Long.getLong("benchmark.sampleMillis", 200L));
            long livenessNanos = TimeUnit.MILLISECONDS.toNanos(
                    Long.getLong("benchmark.livenessMillis", 50L));
            double[] means = new double[repetitions];
            double[] vector;

            while ((vector = generator.get()) != null) {
                Distribution distribution = new Distribution(vector);
                Arrays.fill(means, 0);
                System.out.printf("Vector: (%d / %d)%n", index++, generator.limit);

                actionPicker.setWeights(vector);
                try {
                    for (int repetition = 0; repetition < repetitions; repetition++) {
                        for (var sink : sinks) {
                            sink.resetCounter();
                        }

                        long start = System.nanoTime();
                        long livenessDeadline = start + livenessNanos;
                        long runDeadline = start + sampleNanos;
                        long previous = 0;
                        long current;
                        boolean timedOut = false;

                        while (true) {
                            LockSupport.parkNanos(livenessNanos);
                            long now = System.nanoTime();
                            current = consumed(sinks);
                            if (now >= runDeadline) {
                                break;
                            }
                            if (current == previous && now > livenessDeadline) {
                                timedOut = true;
                                break;
                            }
                            previous = current;
                            livenessDeadline = now + livenessNanos;
                        }

                        double throughput = current / (double) (System.nanoTime() - start);
                        means[repetition] = throughput;
                        distribution.digest.add(throughput);
                        distribution.mean +=
                                (throughput - distribution.mean) / (repetition + 1);
                        if (timedOut) {
                            break;
                        }
                    }
                } finally {
                    actionPicker.setWeights(halt);
                }

                writer.spaceSeparatedWriteLine(distribution.vector);
                writer.spaceSeparatedWriteLine(means);
                TOP_SCORES.add(distribution);
                if (TOP_SCORES.size() > 10) {
                    TOP_SCORES.poll();
                }

                awaitQuiescence(sinks);
            }
        } finally {
            actionPicker.setWeights(halt);
            controlPlane.close();
        }

        printResults(resultsOutput);
        return rawOutput;
    }

    private static long consumed(List<BenchmarkFrameSink> sinks) {
        long current = 0;
        for (var sink : sinks) {
            current += sink.getConsumed();
        }
        return current;
    }

    private static void awaitQuiescence(List<BenchmarkFrameSink> sinks) {
        SpinWait.awaitWhile(() -> {
            long count = 0;
            for (var sink : sinks) {
                count += sink.getConsumed();
                sink.resetCounter();
            }
            return count > 0;
        });
    }

    private static void printResults(Path path) throws Exception {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, "", StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        List<Distribution> results = new ArrayList<>();
        while (!TOP_SCORES.isEmpty()) {
            results.add(TOP_SCORES.poll());
        }
        Collections.reverse(results);

        try (BenchmarkOutputWriter writer = new BenchmarkOutputWriter(path)) {
            writer.writeLine("Top Throughput:");
            for (var distribution : results) {
                writer.writeLine(String.format(
                        "Quantiles: P10: %.8f P25: %.8f P50: %.8f P75: %.8f P90: %.8f AggregateMean: %.8f",
                        distribution.digest.quantile(0.1), distribution.digest.quantile(0.25),
                        distribution.digest.quantile(0.5), distribution.digest.quantile(0.75),
                        distribution.digest.quantile(0.9), distribution.mean));
            }
            writer.writeLine("\n\nTop Weights:");
            for (var distribution : results) {
                writer.writeLine(weightsToCode(distribution.vector));
            }
            writer.writeLine("\n\nBounds:");
            for (var distribution : results) {
                double min = Double.MAX_VALUE;
                double max = -min;
                for (double value : distribution.vector) {
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
                writer.writeLine("[" + min + ", " + max + "]");
            }
        }
    }

    private static String weightsToCode(double[] array) {
        StringJoiner joiner = new StringJoiner(", ");
        for (double value : array) {
            joiner.add(new BigDecimal(value).toPlainString());
        }
        return String.format("double[] weights = new double[]{%s}", joiner);
    }

    private BenchmarkRunner() {

    }

    private static class VectorProducer implements AutoCloseable {

        final SobolSequenceGenerator generator;
        final BenchmarkOutputReader reader;
        final long limit;
        int count;

        VectorProducer(int limit) {
            this.limit = limit;
            this.generator = new SobolSequenceGenerator(28);
            this.generator.skipTo(Integer.getInteger("benchmark.sobolSkip", 1024));
            this.reader = null;
        }

        VectorProducer(Path path) throws Exception {
            this.generator = null;
            this.reader = new BenchmarkOutputReader(path);
            this.limit = this.reader.getLines();
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
                throw new RuntimeException("Failed to read candidate vector", e);
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
