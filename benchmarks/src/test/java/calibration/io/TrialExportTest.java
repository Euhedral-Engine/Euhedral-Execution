package calibration.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.statistics.HighSpeedMetricsStatistics;
import calibration.statistics.fork.ForkCalculationResult;
import calibration.statistics.iteration.IterationResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrialExportTest {

    @Test
    void exportsOnlyRemainingObservationArtifacts(@TempDir Path directory) throws Exception {
        HighSpeedMetrics metrics = new HighSpeedMetrics(8);
        metrics.recordBatchProgress(1, 1, 2, 4, 2, 0, 3, 10.0);
        metrics.recordBatchComplete(1, 1, 2, 4, 2, 0, 3, 10.0, 100.0);
        var system = HighSpeedMetricsStatistics.calculateSystem(0, List.of(metrics));
        var core = HighSpeedMetricsStatistics.calculate(0, 2, metrics);
        var fork = HighSpeedMetricsStatistics.calculateSystemFork(0, List.of(List.of(metrics)));

        TrialExport.exportAll(
                directory,
                new ForkCalculationResult(fork, List.of(new IterationResult(0, system, List.of(core)))),
                false);

        Set<String> files;
        try (var paths = Files.list(directory)) {
            files = paths.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
        }
        assertEquals(
                Set.of(
                        "raw_observations.tsv",
                        "raw_observations.tsv.sha256",
                        "statistics.tsv",
                        "statistics.tsv.sha256",
                        "correlations.tsv",
                        "correlations.tsv.sha256"),
                files);
        assertTrue(Files.readString(directory.resolve("raw_observations.tsv"))
                .startsWith("iteration\tscope\tcore\tbatchProgressTotal\tbatchCompleteTotal"));
    }
}
