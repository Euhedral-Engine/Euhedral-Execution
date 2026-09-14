package io.euhedral_execution.benchmarks.cfd.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/// Statistics are over independent JVM fork means; iterations remain available in raw JMH JSON.
public final class BenchmarkResults {
    private BenchmarkResults() {}

    public record Fork(
            String status,
            String reason,
            String directory,
            Double secondsPerInvocation,
            List<Double> iterations,
            long fluidCells,
            long processElapsedNs) {}

    public record Summary(
            String id,
            String backend,
            Object requestedSources,
            int resolvedSources,
            int workers,
            String status,
            List<Fork> forks,
            Double meanSeconds,
            Double forkStdDevSeconds,
            Double forkCv,
            Double mlups,
            Double speedup,
            Double parallelEfficiency) {}

    public static double mlups(long fluidCells, long steps, double seconds) {
        BenchmarkSuite.require(
                fluidCells > 0 && steps > 0 && Double.isFinite(seconds) && seconds > 0,
                "metrics require finite positive work and time");
        double value = fluidCells * (double) steps / seconds / 1_000_000;
        BenchmarkSuite.require(Double.isFinite(value), "MLUPS overflow");
        return value;
    }

    static Fork read(Path directory, BenchmarkJob job, long processElapsedNs) throws IOException {
        JsonNode raw =
                BenchmarkSuite.JSON.readTree(directory.resolve("jmh.json").toFile());
        JsonNode trial =
                BenchmarkSuite.JSON.readTree(directory.resolve("trial.json").toFile());
        BenchmarkSuite.require(raw.isArray() && raw.size() == 1, "one JMH result required per fork");
        var result = raw.get(0);
        BenchmarkSuite.require(
                result.path("benchmark").asText().equals(CfdBenchmark.class.getName() + ".advance")
                        && result.path("mode").asText().equals("avgt")
                        && result.path("threads").asInt() == 1
                        && result.path("forks").asInt() == 1
                        && result.path("params")
                                .path("jobFile")
                                .asText()
                                .equals(directory.resolve("job.json").toString())
                        && result.path("warmupIterations").asInt() == job.warmupIterations()
                        && result.path("measurementIterations").asInt() == job.measurementIterations()
                        && result.path("primaryMetric")
                                .path("scoreUnit")
                                .asText()
                                .equals("s/op"),
                "incompatible JMH measurement boundary");
        BenchmarkSuite.require(
                trial.path("status").asText().equals("COMPLETED")
                        && trial.path("backendEquivalenceVerified").asBoolean()
                        && trial.path("checkedInvocations").asLong() > 0
                        && trial.path("checkedWarmupInvocations").asLong() > 0
                        && trial.path("fluidCells").asLong() > 0
                        && trial.path("job")
                                .equals(BenchmarkSuite.JSON.readTree(
                                        io.euhedral_execution.benchmarks.cfd.config.ConfigLoader.json(job))),
                "incomplete or mismatched fork validity evidence");
        var data = result.path("primaryMetric").path("rawData");
        BenchmarkSuite.require(
                data.isArray() && data.size() == 1 && data.get(0).size() == job.measurementIterations(),
                "incomplete raw fork measurements");
        var values = new java.util.ArrayList<Double>();
        double sum = 0;
        for (var sample : data.get(0)) {
            double value = sample.asDouble(Double.NaN);
            BenchmarkSuite.require(Double.isFinite(value) && value > 0, "invalid raw iteration time");
            values.add(value);
            sum += value;
        }
        double mean = sum / values.size();
        BenchmarkSuite.require(Double.isFinite(mean) && mean > 0, "invalid fork mean");
        return new Fork(
                "COMPLETED",
                "complete fields verified during warmup and after every measured invocation",
                directory.toString(),
                mean,
                List.copyOf(values),
                trial.path("fluidCells").asLong(),
                processElapsedNs);
    }

    public static Summary summarize(
            BenchmarkSuite.Variant variant,
            int workers,
            int sources,
            List<Fork> forks,
            int expectedForks,
            long steps,
            double maxCv) {
        String status = "PASSED";
        if (forks.size() != expectedForks
                || forks.stream().anyMatch(f -> !f.status().equals("COMPLETED"))) {
            status = forks.stream().anyMatch(f -> f.status().equals("TIMED_OUT")) ? "TIMED_OUT" : "FAILED";
            return new Summary(
                    variant.id(),
                    variant.backend(),
                    variant.sources(),
                    sources,
                    workers,
                    status,
                    List.copyOf(forks),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        long cells = forks.getFirst().fluidCells();
        double mean = 0;
        for (var fork : forks) {
            BenchmarkSuite.require(
                    fork.fluidCells() == cells
                            && cells > 0
                            && fork.secondsPerInvocation() != null
                            && Double.isFinite(fork.secondsPerInvocation())
                            && fork.secondsPerInvocation() > 0,
                    "fork cases/work differ");
            mean += fork.secondsPerInvocation() / forks.size();
        }
        double variance = 0;
        for (var fork : forks) {
            double difference = fork.secondsPerInvocation() - mean;
            variance += difference * difference;
        }
        Double sd = forks.size() > 1 ? Math.sqrt(variance / (forks.size() - 1)) : null;
        Double cv = sd == null ? null : sd / mean;
        if (cv == null) {
            status = "INSUFFICIENT_FORKS";
        } else if (!Double.isFinite(cv) || cv > maxCv) {
            status = "UNSTABLE";
        }
        return new Summary(
                variant.id(),
                variant.backend(),
                variant.sources(),
                sources,
                workers,
                status,
                List.copyOf(forks),
                mean,
                sd,
                cv,
                status.equals("PASSED") ? mlups(cells, steps, mean) : null,
                null,
                null);
    }

    static Summary compare(Summary candidate, Summary baseline, Summary serial) {
        return new Summary(
                candidate.id(),
                candidate.backend(),
                candidate.requestedSources(),
                candidate.resolvedSources(),
                candidate.workers(),
                candidate.status(),
                candidate.forks(),
                candidate.meanSeconds(),
                candidate.forkStdDevSeconds(),
                candidate.forkCv(),
                candidate.mlups(),
                candidate.status().equals("PASSED")
                                && baseline != null
                                && baseline.status().equals("PASSED")
                        ? baseline.meanSeconds() / candidate.meanSeconds()
                        : null,
                candidate.status().equals("PASSED")
                                && serial != null
                                && serial.status().equals("PASSED")
                        ? serial.meanSeconds() / (candidate.workers() * candidate.meanSeconds())
                        : null);
    }
}
