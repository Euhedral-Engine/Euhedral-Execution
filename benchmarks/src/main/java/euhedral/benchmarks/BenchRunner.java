package euhedral.benchmarks;

import euhedral.benchmarks.core_benchmarks.BatchedMandelbrotBenchmark;
import euhedral.benchmarks.core_benchmarks.EndToEndLatencyBenchmark;
import euhedral.benchmarks.core_benchmarks.HighScaleBenchmark;
import euhedral.benchmarks.core_benchmarks.MandelbrotBenchmark;
import euhedral.benchmarks.core_benchmarks.ThroughputComparisonBenchmark;
import euhedral.benchmarks.core_benchmarks.TrueThroughputBenchmark;
import euhedral.benchmarks.queue_benchmarks.MPMCBenchmarks;
import euhedral.benchmarks.queue_benchmarks.MPSCBenchmarks;
import euhedral.benchmarks.queue_benchmarks.SPSCBenchmarks;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class BenchRunner {

    private static final List<String> FLAGS =
            List.of("-XX:+UseThreadPriorities", "--enable-native-access=ALL-UNNAMED",
                    "--sun-misc-unsafe-memory-access=allow", "--add-exports",
                    "java.base/jdk.internal.platform=ALL-UNNAMED", "--add-exports",
                    "java.base/jdk.internal.vm.annotation=ALL-UNNAMED", "--add-opens",
                    "java.base/java.util=ALL-UNNAMED",
                    "-Dorg.slf4j.simpleLogger.defaultLogLevel=error");

    public static void main(String[] args) throws Exception {
        Set<String> benchmarks =
                new TreeSet<>(
                        Set.of("all", "core-high-scale", "core-latency", "core-throughput", "core-throughput-comp",
                                "batched-mandelbrot", "mandelbrot", "queues-spsc", "queues-mpsc", "queues-mpmc"));
        if (args.length == 0) {
            System.out.println("Please specify a benchmark to run. Options: " + benchmarks);
            return;
        }

        Set<String> tasks = new LinkedHashSet<>();
        for (String a : args) {
            String name = a.trim().toLowerCase();
            if (!benchmarks.contains(name)) {
                System.out.println("Unknown benchmark: " + name);
                System.out.println(
                        "Please specify a valid benchmark to run. Options: " + benchmarks);
                return;
            }
            if (name.equals("all")) {
                System.out.println("Warning! Running all benchmarks. This could take a while...");
                Thread.sleep(1000);
                tasks = benchmarks;
                break;
            }
            tasks.add(name);
        }

        boolean gc = "true".equalsIgnoreCase(System.getProperty("gc", "false").trim());
        boolean perf = "true".equalsIgnoreCase(System.getProperty("perf", "false").trim());
        for (String task : tasks) {
            if (task.equals("all")) {
                continue;
            }
            Class<?> benchmark;
            List<String> flags = new ArrayList<>(FLAGS);
            switch (task) {
                case "batched-mandelbrot" -> {
                    benchmark = BatchedMandelbrotBenchmark.class;
                    String degree = System.getProperty("degree", "2");
                    flags.add("-Ddegree=" + degree);
                }
                case "mandelbrot" -> {
                    benchmark = MandelbrotBenchmark.class;
                    String degree = System.getProperty("degree", "2");
                    flags.add("-Ddegree=" + degree);

                    String dir = System.getProperty("outputDir");
                    String fileName = System.getProperty("outputFile");
                    if (dir != null && !dir.isBlank()) {
                        if (fileName == null || fileName.isBlank()) {
                            continue;
                        } else if (fileName.endsWith(".png")) {
                            fileName += ".png";
                        }
                        flags.add("-DoutputDir=" + dir);
                        flags.add("-DoutputFile=" + fileName);
                    } else if (fileName != null && !fileName.isBlank()) {

                        if (!fileName.endsWith(".png")) {
                            fileName += ".png";
                        }
                        flags.add("-DoutputFile=" + fileName);
                    }
                }
                case "core-high-scale" -> {
                    benchmark = HighScaleBenchmark.class;
                    flags.add("-DXms100g");
                    flags.add("-DXmx100g");
                }
                case "core-throughput" -> benchmark = TrueThroughputBenchmark.class;
                case "core-throughput-comp" -> benchmark = ThroughputComparisonBenchmark.class;
                case "core-latency" -> benchmark = EndToEndLatencyBenchmark.class;
                case "queues-spsc" -> benchmark = SPSCBenchmarks.class;
                case "queues-mpmc" -> benchmark = MPMCBenchmarks.class;
                case "queues-mpsc" -> benchmark = MPSCBenchmarks.class;
                default -> throw new IllegalArgumentException("Unknown benchmark: " + task);
            }

            ChainedOptionsBuilder opt = new OptionsBuilder().include(benchmark.getName())
                    .jvmArgsAppend(flags.toArray(new String[0]));
            if (gc) {
                opt.addProfiler("gc");
            }
            if (perf) {
                opt.addProfiler("perf",
                        "events=cycles,instructions,cache-misses,L1-dcache-loads,L1-dcache-load-misses,L1-icache-loads,L1-icache-load-misses,dTLB-loads,dTLB-load-misses,branch-loads,branch-misses");

            }
            new Runner(opt.build()).run();
        }
    }
}
