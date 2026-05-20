package euhedral.benchmarks;

import euhedral.benchmarks.core_benchmarks.EndToEndLatencyBenchmark;
import euhedral.benchmarks.core_benchmarks.MandelbrotBenchmark;
import euhedral.benchmarks.core_benchmarks.ThroughputBenchmark;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class BenchRunner {
    private static final List<String> FLAGS = List.of("-XX:+UseThreadPriorities",
            "--enable-native-access=ALL-UNNAMED",
            "--sun-misc-unsafe-memory-access=allow",
            "--add-exports", "java.base/jdk.internal.platform=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "-Dorg.slf4j.simpleLogger.defaultLogLevel=error");
    public static void main(String[] args) throws Exception {
        Set<String> benchmarks = Set.of("core-e2e-latency", "core-throughput", "mandelbrot");
        if (args.length == 0) {
            System.out.println(
                    "Please specify a benchmark to run. Options: " + benchmarks);
            return;
        }

        Set<String> tasks = new HashSet<>();
        for(String a : args) {
            String name = a.trim().toLowerCase();
            if(!benchmarks.contains(name)) {
                System.out.println("Unknown benchmark: " + name);
                System.out.println(
                        "Please specify a benchmark to run. Options: " +  benchmarks);
            }
            tasks.add(name);
        }

        for(String task : tasks) {
            Class<?> benchmark;
            List<String> flags = new ArrayList<>(FLAGS);
            switch (task) {
                case "mandelbrot" -> {
                    benchmark = MandelbrotBenchmark.class;
                    String degree = System.getProperty("degree", "2");
                    flags.add("-Ddegree=" + degree);

                    String dir = System.getProperty("outputDir");
                    String fileName = System.getProperty("outputFile");
                    if(dir != null && !dir.isBlank()) {
                        flags.add("-DoutputDir=" + System.getProperty("outputDir"));
                        if(fileName == null || fileName.isBlank()) {
                            fileName = "mandelbrot-D" + degree + ".png";
                        } else if (fileName.endsWith(".png")) {
                            fileName += ".png";
                        }
                        flags.add("-DoutputFile=" + fileName);
                    } else if (fileName != null && !fileName.isBlank()) {

                        if (!fileName.endsWith(".png")) {
                            fileName += ".png";
                        }
                        flags.add("-DoutputFile=" + fileName);
                    }
                }
                case "core-throughput" -> benchmark = ThroughputBenchmark.class;
                case "core-e2e-latency" -> benchmark = EndToEndLatencyBenchmark.class;
                default -> throw new IllegalArgumentException("Unknown benchmark: " + task);
            }

            ChainedOptionsBuilder opt =
                    new OptionsBuilder().include(benchmark.getSimpleName())
                            .addProfiler("gc")
//                        .addProfiler("perf",
//                                "events=cycles,instructions,cache-misses,L2-loads,L2-load-misses")
                            .jvmArgsAppend(flags.toArray(new String[0]));
            new Runner(opt.build()).run();
        }
    }
}
