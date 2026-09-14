package io.euhedral_execution.benchmarks.cfd.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.benchmarks.cfd.execution.BackendOptions;
import io.euhedral_execution.benchmarks.cfd.execution.WorkerBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForkEvidenceTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "incomplete",
                "nan",
                "zero",
                "wrong-unit",
                "wrong-job",
                "unverified",
                "no-invocations",
                "no-checked-warmup"
            })
    void incompleteOrMismatchedForksCannotSupplyScores(String mutation) throws Exception {
        var job = job();
        var raw = raw();
        var trial = trial(job);
        switch (mutation) {
            case "incomplete" ->
                ((ObjectNode) raw.path("primaryMetric"))
                        .putArray("rawData")
                        .addArray()
                        .add(.25);
            case "nan" ->
                ((ObjectNode) raw.path("primaryMetric"))
                        .putArray("rawData")
                        .addArray()
                        .add("NaN")
                        .add(.5);
            case "zero" ->
                ((ObjectNode) raw.path("primaryMetric"))
                        .putArray("rawData")
                        .addArray()
                        .add(0)
                        .add(.5);
            case "wrong-unit" -> ((ObjectNode) raw.path("primaryMetric")).put("scoreUnit", "ms/op");
            case "wrong-job" -> ((ObjectNode) raw.path("params")).put("jobFile", "other");
            case "unverified" -> trial.put("backendEquivalenceVerified", false);
            case "no-invocations" -> trial.put("checkedInvocations", 0);
            case "no-checked-warmup" -> trial.put("checkedWarmupInvocations", 0);
        }
        save(raw, trial);
        assertThrows(IllegalArgumentException.class, () -> BenchmarkResults.read(directory, job, 10));
    }

    @Test
    void completedForkRetainsAllRawIterationsAndExplicitWork() throws Exception {
        var job = job();
        save(raw(), trial(job));
        var fork = BenchmarkResults.read(directory, job, 10);
        assertEquals("COMPLETED", fork.status());
        assertEquals(.375, fork.secondsPerInvocation());
        assertEquals(2, fork.iterations().size());
        assertEquals(500, fork.fluidCells());
        assertEquals(10, fork.processElapsedNs());
        Files.delete(directory.resolve("trial.json"));
        assertThrows(java.io.IOException.class, () -> BenchmarkResults.read(directory, job, 10));
    }

    private void save(ObjectNode raw, ObjectNode trial) throws Exception {
        Files.writeString(
                directory.resolve("jmh.json"),
                BenchmarkSuite.JSON.createArrayNode().add(raw).toString());
        Files.writeString(directory.resolve("trial.json"), trial.toString());
    }

    private ObjectNode raw() {
        var raw = BenchmarkSuite.JSON
                .createObjectNode()
                .put("benchmark", CfdBenchmark.class.getName() + ".advance")
                .put("mode", "avgt")
                .put("threads", 1)
                .put("forks", 1)
                .put("warmupIterations", 1)
                .put("measurementIterations", 2);
        raw.putObject("params").put("jobFile", directory.resolve("job.json").toString());
        raw.putObject("primaryMetric")
                .put("scoreUnit", "s/op")
                .putArray("rawData")
                .addArray()
                .add(.25)
                .add(.5);
        return raw;
    }

    private ObjectNode trial(BenchmarkJob job) throws Exception {
        var value = BenchmarkSuite.JSON
                .createObjectNode()
                .put("status", "COMPLETED")
                .put("backendEquivalenceVerified", true)
                .put("checkedInvocations", 2)
                .put("checkedWarmupInvocations", 1)
                .put("fluidCells", 500);
        value.set("job", BenchmarkSuite.JSON.readTree(ConfigLoader.json(job)));
        return value;
    }

    private BenchmarkJob job() {
        return new BenchmarkJob(
                "configuration",
                directory.toString(),
                "reference",
                "sha",
                "validation",
                "sha",
                "case",
                BenchmarkSuite.ValidationScope.EXACT,
                "caseid",
                "numerical",
                "artifact",
                new BackendOptions("fjp", 1, null, null, false, null),
                new WorkerBudget(new int[] {1}, new int[] {1}, 1, 0, "EXACT"),
                90,
                10,
                1,
                2,
                100,
                10000);
    }
}
