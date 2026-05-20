package euhedral.benchmarks;

import euhedral.benchmarks.core_benchmarks.LatencyBenchmark;
import euhedral.benchmarks.core_benchmarks.MandelbrotBenchmark;
import euhedral.benchmarks.core_benchmarks.ThroughputBenchmark;
import java.util.ArrayList;
import java.util.List;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class BenchRunner {
    private static final List<String> FLAGS = List.of("-XX:+UseThreadPriorities",
            "--enable-native-access=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.platform=ALL-UNNAMED",
            "--add-exports", "java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
            "--add-opens", "java.base/java.util=ALL-UNNAMED",
            "-Dorg.slf4j.simpleLogger.defaultLogLevel=error");
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println(
                    "Please specify a benchmark to run. Options: [core-latency, core-throughput, mandelbrot]");
            return;
        }

        String name = args[0].toLowerCase();

        Class<?> benchmark;
        List<String> flags = new ArrayList<>(FLAGS);
        switch (name) {
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
            case "core-latency" -> benchmark = LatencyBenchmark.class;
            default -> {
                System.out.println("Unknown benchmark: " + name);
                System.out.println(
                        "Please specify a benchmark to run. Options: [core-latency, core-throughput, mandelbrot]");
                return;
            }
        }

        ChainedOptionsBuilder opt =
                new OptionsBuilder().include(benchmark.getSimpleName())
                        .forks(1)
                        .addProfiler("gc")
//                        .addProfiler("perf",
//                                "events=cycles,instructions,cache-misses,L2-loads,L2-load-misses")
                        .jvmArgsAppend(flags.toArray(new String[0]));
        new Runner(opt.build()).run();
    }
}
