package io.euhedral_execution.benchmarks.cfd.benchmark;

import java.nio.file.Path;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/// One controller launches one isolated JMH fork; the suite runner bounds both processes together.
public final class BenchmarkFork {
    private BenchmarkFork() {}

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]).toAbsolutePath();
        var job = BenchmarkSuite.JSON.readValue(file.toFile(), BenchmarkJob.class);
        var options = new OptionsBuilder()
                .include("^" + CfdBenchmark.class.getName() + ".advance$")
                .param("jobFile", file.toString())
                .threads(1)
                .forks(1)
                .warmupForks(0)
                .warmupIterations(job.warmupIterations())
                .measurementIterations(job.measurementIterations())
                .warmupTime(TimeValue.milliseconds(job.iterationMillis()))
                .measurementTime(TimeValue.milliseconds(job.iterationMillis()))
                .timeout(TimeValue.milliseconds(job.processDeadlineMillis()))
                .shouldFailOnError(true)
                .resultFormat(ResultFormatType.JSON)
                .result(Path.of(job.directory()).resolve("jmh.json").toString())
                .build();
        new Runner(options).run();
    }
}
