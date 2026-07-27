package io.euhedral_execution.training;

import io.euhedral_execution.core.config.FragmentActionPicker;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.control_plane.ControlPlaneShard;
import io.euhedral_execution.core.frames.BenchmarkFrame;
import io.euhedral_execution.core.impl.BaseCloneableObject;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.apache.commons.math4.legacy.random.SobolSequenceGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BenchmarkRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(BenchmarkRunner.class);
    private static final PriorityQueue<Distribution> TOP_SCORES = new PriorityQueue<>(11);

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

    /** Legacy single-configuration entry point. */
    public static Path run(Path candidates, Path rawOutput, Path resultsOutput) throws Exception {
        return runConfiguration(legacySourceCount(), candidates, rawOutput, resultsOutput);
    }

    /** Legacy single-configuration Sobol entry point. */
    public static Path run(int sobolCount, Path rawOutput, Path resultsOutput) throws Exception {
        int sourceCount = legacySourceCount();
        try (VectorProducer producer = new VectorProducer(sobolCount)) {
            return runConfiguration(sourceCount, producer, rawOutput, resultsOutput);
        }
    }

    /**
     * Benchmarks the same policy set under a deterministic rotating subset of configured source
     * counts. Every configuration is written separately so DataMerger normalizes it independently.
     */
    public static List<BenchmarkRun> runAcrossSourceCounts(Path candidates, Path rawDirectory,
            Path resultsDirectory, int iteration) throws Exception {
        Files.createDirectories(rawDirectory);
        Files.createDirectories(resultsDirectory);

        int[] selected = selectedSourceCounts(iteration);
        List<BenchmarkRun> runs = new ArrayList<>(selected.length);
        for (int sourceCount : selected) {
            Path raw = rawDirectory.resolve(String.format("source-%04d.txt", sourceCount));
            Path results = resultsDirectory.resolve(String.format("source-%04d.txt", sourceCount));
            runConfiguration(sourceCount, candidates, raw, results);
            runs.add(new BenchmarkRun(sourceCount, raw, results));
        }
        return List.copyOf(runs);
    }

    public static int[] selectedSourceCounts(int iteration) {
        if (iteration <= 0) {
            throw new IllegalArgumentException("iteration must be positive");
        }
        int[] configured = configuredSourceCounts();
        int perIteration = Integer.getInteger("benchmark.sourceConfigurationsPerIteration",
                Math.min(2, configured.length));
        perIteration = Math.max(1, Math.min(perIteration, configured.length));

        int[] selected = new int[perIteration];
        int start = Math.floorMod((iteration - 1) * perIteration, configured.length);
        for (int i = 0; i < selected.length; i++) {
            selected[i] = configured[(start + i) % configured.length];
        }
        return selected;
    }

    public static int[] configuredSourceCounts() {
        int cores = Math.max(1, SystemInfo.getCoreCount());
        Set<Integer> counts = new LinkedHashSet<>();
        String explicit = System.getProperty("benchmark.sourceCounts");
        if (explicit != null && !explicit.isBlank()) {
            for (String value : explicit.split(",")) {
                int count = Integer.parseInt(value.trim());
                counts.add(Math.max(1, Math.min(count, cores)));
            }
        } else {
            String ratios = System.getProperty("benchmark.sourceRatios", "0.25,0.5,1.0");
            for (String value : ratios.split(",")) {
                double ratio = Double.parseDouble(value.trim());
                if (!Double.isFinite(ratio) || ratio <= 0) {
                    throw new IllegalArgumentException(
                            "benchmark.sourceRatios values must be positive and finite");
                }
                counts.add(Math.max(1, Math.min((int) Math.round(cores * ratio), cores)));
            }
        }
        if (counts.isEmpty()) {
            counts.add(cores);
        }
        return counts.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    private static int legacySourceCount() {
        String sourceRatio = System.getProperty("sourceRatio");
        if (sourceRatio == null || sourceRatio.isBlank()) {
            return SystemInfo.getCoreCount();
        }
        double ratio = Double.parseDouble(sourceRatio);
        return Math.max(1, Math.min((int) Math.round(SystemInfo.getCoreCount() * ratio),
                SystemInfo.getCoreCount()));
    }

    private static Path runConfiguration(int sourceCount, Path candidates, Path rawOutput,
            Path resultsOutput) throws Exception {
        try (VectorProducer producer = new VectorProducer(candidates)) {
            return runConfiguration(sourceCount, producer, rawOutput, resultsOutput);
        }
    }

    private static Path runConfiguration(int sourceCount, VectorProducer generator, Path rawOutput,
            Path resultsOutput) throws Exception {
        if (rawOutput.getParent() != null) {
            Files.createDirectories(rawOutput.getParent());
        }
        TOP_SCORES.clear();
        ThreadTools.setAffinity(SystemInfo.getCoreInfo(0).getCpuSet().nextSetBit(0));

        double[] halt = new double[28];
        FragmentActionPicker actionPicker = new FragmentActionPicker(halt);
        LatticeConfig config = new LatticeConfig("Benchmark-" + sourceCount,
                SystemInfo.getCpuSet(), Duration.ofSeconds(1),
                ControlPlaneShard.createBaseShard("Shard",
                        new BaseCloneableObject(FragmentConfig.ofBenchmark(actionPicker))));
        ControlPlaneLattice controlPlane = ControlPlaneLattice.getOrCreate(config);
        Duration resetTimeout = Duration.ofMillis(
                Long.getLong("benchmark.resetTimeoutMillis", 2_000L));

        List<BenchmarkFrameSink> sinks = createSinks(sourceCount);
        try (BenchmarkOutputWriter writer = new BenchmarkOutputWriter(rawOutput)) {
            controlPlane.start();
            for (BenchmarkFrameSink sink : sinks) {
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

            LOGGER.info("Benchmarking {} policies with {} frame sources", generator.limit,
                    sourceCount);
            while ((vector = generator.get()) != null) {
                Distribution distribution = new Distribution(vector);
                Arrays.fill(means, 0);
                LOGGER.info("Sources: {} Vector: ({} / {})", sourceCount, index++,
                        generator.limit);

                actionPicker.setWeights(halt);
                pauseAll(sinks, resetTimeout);
                ControlPlaneLattice.CacheReset reset = controlPlane.resetForNextTrial(resetTimeout);
                if (reset.clearedFrames() > 0) {
                    LOGGER.debug("Cleared {} buffered frames before the next policy trial",
                            reset.clearedFrames());
                }
                for (BenchmarkFrameSink sink : sinks) {
                    sink.resetCounter();
                }

                actionPicker.setWeights(vector);
                resumeAll(sinks);
                try {
                    for (int repetition = 0; repetition < repetitions; repetition++) {
                        for (BenchmarkFrameSink sink : sinks) {
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
                    pauseAll(sinks, resetTimeout);
                }

                writer.spaceSeparatedWriteLine(distribution.vector);
                writer.spaceSeparatedWriteLine(means);
                TOP_SCORES.add(distribution);
                if (TOP_SCORES.size() > 10) {
                    TOP_SCORES.poll();
                }
            }
        } finally {
            actionPicker.setWeights(halt);
            for (BenchmarkFrameSink sink : sinks) {
                try {
                    sink.hardStop(resetTimeout);
                } catch (Exception error) {
                    LOGGER.warn("Failed to hard-stop benchmark source", error);
                }
            }
            controlPlane.close();
        }

        printResults(resultsOutput);
        return rawOutput;
    }

    private static List<BenchmarkFrameSink> createSinks(int sources) {
        int framesPerSource = Integer.getInteger("benchmark.framesPerSource", 100_000);
        List<BenchmarkFrameSink> sinks = new ArrayList<>(sources);
        for (int i = 0; i < sources; i++) {
            BenchmarkFrame[] frames = BenchmarkFrame.generate(framesPerSource, false,
                    ThreadLocalRandom.current().nextLong());
            sinks.add(new BenchmarkFrameSink(frames));
        }
        return sinks;
    }

    private static void pauseAll(List<BenchmarkFrameSink> sinks, Duration timeout) {
        for (BenchmarkFrameSink sink : sinks) {
            sink.pause(timeout);
        }
    }

    private static void resumeAll(List<BenchmarkFrameSink> sinks) {
        for (BenchmarkFrameSink sink : sinks) {
            sink.resume();
        }
    }

    private static long consumed(List<BenchmarkFrameSink> sinks) {
        long current = 0;
        for (BenchmarkFrameSink sink : sinks) {
            current += sink.getConsumed();
        }
        return current;
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
            for (Distribution distribution : results) {
                writer.writeLine(String.format(
                        "Quantiles: P10: %.8f P25: %.8f P50: %.8f P75: %.8f P90: %.8f AggregateMean: %.8f",
                        distribution.digest.quantile(0.1), distribution.digest.quantile(0.25),
                        distribution.digest.quantile(0.5), distribution.digest.quantile(0.75),
                        distribution.digest.quantile(0.9), distribution.mean));
            }
            writer.writeLine("\n\nTop Weights:");
            for (Distribution distribution : results) {
                writer.writeLine(weightsToCode(distribution.vector));
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

    public record BenchmarkRun(int sourceCount, Path rawOutput, Path resultsOutput) {
    }

    private static final class VectorProducer implements AutoCloseable {

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
            } catch (Exception error) {
                throw new RuntimeException("Failed to read candidate vector", error);
            }
        }

        @Override
        public void close() throws Exception {
            if (this.reader != null) {
                this.reader.close();
            }
        }
    }

    private BenchmarkRunner() {
    }
}
