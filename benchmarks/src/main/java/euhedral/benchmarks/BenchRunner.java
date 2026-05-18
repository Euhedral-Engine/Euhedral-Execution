package euhedral.benchmarks;

import euhedral.benchmarks.core_benchmarks.MandelbrotBenchmark;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class BenchRunner {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Please specify a benchmark to run. Options: mandelbrot");
            return;
        }

        String name = args[0].toLowerCase();
        Class<?> benchmark;
        if (name.equals("mandelbrot")) {
            benchmark = MandelbrotBenchmark.class;
        } else {
            System.out.println("Unknown benchmark: " + name);
            System.out.println("Please specify a benchmark to run. Options: mandelbrot");
            return;
        }

        String degree = System.getProperty("degree", "5"); // default to 5 if not passed
        String outputFileName = System.getProperty("outputDir", "mandelbrot-D" + degree);
        String outputDir = System.getProperty("outputDir", "");

        if(!outputFileName.endsWith(".png")) {
            outputFileName += ".png";
        }

        ChainedOptionsBuilder opt =
                new OptionsBuilder().include(benchmark.getSimpleName())
                        .warmupIterations(0)
                        .forks(1)
                        .measurementIterations(1)
                        .addProfiler("gc")
                        .addProfiler("perf",
                                "events=cycles,instructions,cache-misses,L2-loads,L2-load-misses")
                        .jvmArgs("-XX:+RestrictContended", "-XX:+UseThreadPriorities",
                                "--enable-native-access=ALL-UNNAMED",
                                "--add-exports", "java.base/jdk.internal.platform=ALL-UNNAMED",
                                "--add-exports", "java.base/jdk.internal.vm.annotation=ALL-UNNAMED",
                                "--add-opens", "java.base/java.util=ALL-UNNAMED",
                                "-Ddegree=" + degree, "-DoutputFile=" + outputFileName,
                                "-DoutputDir=" + outputDir,
                                "-Dorg.slf4j.simpleLogger.defaultLogLevel=error");
        new Runner(opt.build()).run();
    }
}
