package io.euhedral_execution.benchmarks;

import io.euhedral_execution.benchmarks.core_benchmarks.BatchedMandelbrotBenchmark;
import io.euhedral_execution.benchmarks.core_benchmarks.EndToEndLatencyBenchmark;
import io.euhedral_execution.benchmarks.core_benchmarks.HighContentionThroughput;
import io.euhedral_execution.benchmarks.core_benchmarks.HighScaleBenchmark;
import io.euhedral_execution.benchmarks.core_benchmarks.LightContentionThroughput;
import io.euhedral_execution.benchmarks.core_benchmarks.MandelbrotBenchmark;
import io.euhedral_execution.benchmarks.queue_benchmarks.MPMCBenchmarks;
import io.euhedral_execution.benchmarks.queue_benchmarks.MPSCBenchmarks;
import io.euhedral_execution.benchmarks.queue_benchmarks.SPSCBenchmarks;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BenchRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(BenchRunner.class);

    private static final Set<String> BENCHMARKS = new TreeSet<>(Set.of(
            "all",
            "core-high-scale",
            "core-latency",
            "core-hc-throughput",
            "core-lc-throughput",
            "batched-mandelbrot",
            "mandelbrot",
            "queues-spsc",
            "queues-mpsc",
            "queues-mpmc"));
    private static final List<String> FLAGS = List.of(
            "-XX:+UseThreadPriorities",
            "--enable-native-access=ALL-UNNAMED",
            "--add-exports",
            "java.base/jdk.internal.platform=ALL-UNNAMED",
            "--add-exports",
            "java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
            "-Dorg.slf4j.simpleLogger.defaultLogLevel=error");

    private static Set<String> getBenchmarks(String[] args) {
        Set<String> tasks = new LinkedHashSet<>();
        for (String a : args) {
            String name = a.trim().toLowerCase();
            if (!BENCHMARKS.contains(name)) {
                LOGGER.error("Unknown benchmark: {}", name);
                LOGGER.error("Please specify a valid benchmark to run. Options: {}", BENCHMARKS);
                return Set.of();
            }
            if (name.equals("all")) {
                LOGGER.warn("Warning! Running all benchmarks. This could take a while...");
                return BENCHMARKS;
            }
            tasks.add(name);
        }
        return tasks;
    }

    private static void configureMandelbrot(List<String> flags) {
        String degree = System.getProperty("degree", "2");
        flags.add("-Ddegree=" + degree);

        String dir = System.getProperty("outputDir");
        String fileName = System.getProperty("outputFile");

        boolean dirEmpty = dir == null || dir.isBlank();
        boolean fileEmpty = fileName == null || fileName.isBlank();

        if (!dirEmpty) {
            if (fileEmpty) {
                fileName = String.format("mandelbrot-D%s.png", degree);
            } else if (!fileName.endsWith(".png")) {
                fileName += ".png";
            }
            flags.add("-DoutputDir=" + dir);
            flags.add("-DoutputFile=" + fileName);
        } else if (!fileEmpty) {
            if (!fileName.endsWith(".png")) {
                fileName += ".png";
            }
            flags.add("-DoutputFile=" + fileName);
        }
    }

    private static void addProfilers(ChainedOptionsBuilder opt) {
        boolean gc = "true".equalsIgnoreCase(System.getProperty("gc", "false").trim());
        boolean perf =
                "true".equalsIgnoreCase(System.getProperty("perf", "false").trim());
        if (gc) {
            opt.addProfiler("gc");
        }
        if (perf) {
            opt.addProfiler(
                    "perf",
                    "events=cycles,instructions,cache-misses,L1-dcache-loads,L1-dcache-load-misses,L1-icache-loads,L1-icache-load-misses,dTLB-loads,dTLB-load-misses,branch-loads,branch-misses");
        }
    }

    public static void main(String[] args) throws Exception {

        if (args.length == 0) {
            LOGGER.error("Please specify a benchmark to run. Options: {}", BENCHMARKS);
            return;
        }

        Set<String> tasks = getBenchmarks(args);
        if (tasks.isEmpty()) {
            return;
        }

        List<String> flags = new ArrayList<>(FLAGS);
        StringJoiner tests = new StringJoiner("|");
        for (String task : tasks) {
            if (task.equals("all")) {
                continue;
            }
            Class<?> benchmark;
            switch (task) {
                case "batched-mandelbrot" -> {
                    benchmark = BatchedMandelbrotBenchmark.class;
                    String degree = System.getProperty("degree", "2");
                    flags.add("-Ddegree=" + degree);
                }
                case "mandelbrot" -> {
                    benchmark = MandelbrotBenchmark.class;
                    configureMandelbrot(flags);
                }
                case "core-high-scale" -> {
                    benchmark = HighScaleBenchmark.class;
                    flags.add("-DXms100g");
                    flags.add("-DXmx100g");
                }
                case "core-latency" -> benchmark = EndToEndLatencyBenchmark.class;
                case "core-hc-throughput" -> benchmark = HighContentionThroughput.class;
                case "core-lc-throughput" -> benchmark = LightContentionThroughput.class;
                case "queues-spsc" -> benchmark = SPSCBenchmarks.class;
                case "queues-mpmc" -> benchmark = MPMCBenchmarks.class;
                case "queues-mpsc" -> benchmark = MPSCBenchmarks.class;
                default -> throw new IllegalArgumentException("Unknown benchmark: " + task);
            }

            tests.add(benchmark.getName());
        }
        ChainedOptionsBuilder opt =
                new OptionsBuilder().include(tests.toString()).jvmArgsAppend(flags.toArray(new String[0]));
        addProfilers(opt);
        new Runner(opt.build()).run();
    }
}
