package calibration.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import calibration.comparisons.PerformanceComparisonCalculator;
import calibration.comparisons.TrialConfigDiffer;
import calibration.comparisons.schema.CandidateComparison;
import calibration.comparisons.schema.ComparisonResult;
import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.ThroughputResult;
import calibration.config.ComparisonStrategy;
import calibration.config.TrialConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComparisonExportTest {

    @Test
    void exportsOnlyForkComparisonArtifacts(@TempDir Path directory) throws Exception {
        CompletedRun baseline = run("baseline", List.of(98.0, 100.0, 102.0));
        CompletedRun candidate = run("candidate", List.of(118.0, 120.0, 122.0));
        CandidateComparison comparison = new CandidateComparison(
                0,
                baseline.identity(),
                candidate.identity(),
                null,
                TrialConfigDiffer.diff(baseline.trialConfig(), candidate.trialConfig()),
                PerformanceComparisonCalculator.compare(baseline, candidate));

        ComparisonExport.export(
                directory,
                new ComparisonResult(ComparisonStrategy.BASELINE, List.of(comparison), null, List.of(), List.of()));

        Set<String> files;
        try (var paths = Files.list(directory)) {
            files = paths.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
        assertEquals(
                Set.of(
                        "comparison_manifest.json",
                        "comparison_manifest.json.sha256",
                        "comparison_summary.tsv",
                        "comparison_summary.tsv.sha256",
                        "configuration_differences.tsv",
                        "configuration_differences.tsv.sha256"),
                files);
        assertFalse(
                Files.readString(directory.resolve("comparison_summary.tsv")).contains("compatibility"));
    }

    private static CompletedRun run(String id, List<Double> scores) {
        TrialConfig trial = new TrialConfig(3, 1, 2, List.of(), CompletedRunLoaderTest.benchmarkConfig());
        double mean = scores.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return new CompletedRun(
                new RunIdentity(id, id, null, 0, "/tmp/" + id), trial, new ThroughputResult(mean, "ops/s", scores));
    }
}
