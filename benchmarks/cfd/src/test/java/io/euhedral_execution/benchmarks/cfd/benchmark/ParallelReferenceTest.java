package io.euhedral_execution.benchmarks.cfd.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.execution.BackendOptions;
import io.euhedral_execution.benchmarks.cfd.execution.WorkerBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ParallelReferenceTest {
    @TempDir
    Path directory;

    @Test
    void referenceRequiresCompletedEvidenceFromTheCurrentArtifactWithoutSerialReplay() throws Exception {
        var metadata = BenchmarkSuite.JSON
                .createObjectNode()
                .put("status", "COMPLETED")
                .put("backend", "fjp")
                .put("caseIdentity", "caseid")
                .put("artifactIdentity", "artifact")
                .put("referenceSha256", "sha");
        Path file = directory.resolve("reference.json");
        Files.writeString(file, metadata.toString());
        assertEquals("fjp", PopulationReference.requireEvidence(job()));
        for (String key :
                java.util.List.of("status", "caseIdentity", "artifactIdentity", "referenceSha256", "backend")) {
            var changed = metadata.deepCopy().put(key, "stale-or-invalid");
            Files.writeString(file, changed.toString());
            assertThrows(IllegalArgumentException.class, () -> PopulationReference.requireEvidence(job()), key);
        }
    }

    private BenchmarkJob job() {
        return new BenchmarkJob(
                "configuration",
                directory.toString(),
                directory.resolve("populations.bin").toString(),
                "sha",
                directory.resolve("external.json").toString(),
                "sha",
                "obstacle",
                BenchmarkSuite.ValidationScope.EXACT,
                "caseid",
                "numerical",
                "artifact",
                new BackendOptions("fjp", 1, null, null, false, null),
                new WorkerBudget(new int[] {1}, new int[] {1}, 1, 0, "EXACT"),
                0,
                100,
                1,
                1,
                20,
                60000);
    }
}
