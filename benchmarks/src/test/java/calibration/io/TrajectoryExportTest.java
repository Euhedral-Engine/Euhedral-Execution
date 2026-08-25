package calibration.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.config.CalibrationLifecycleMode;
import calibration.infra.BenchmarkObserver.HighSpeedMetrics;
import calibration.infra.Constants;
import calibration.statistics.HighSpeedMetricsStatistics;
import calibration.statistics.fork.ForkCalculationResult;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.CoreIterationResult;
import calibration.statistics.iteration.IterationResult;
import calibration.statistics.iteration.SystemIterationResult;
import calibration.statistics.iteration.TrajectoryWindow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrajectoryExportTest {

    @Test
    void continuousTrajectoryRetainsOrderedWindowEvidenceAndDeterministicChecksums(@TempDir Path tempDir)
            throws Exception {
        HighSpeedMetrics metrics = new HighSpeedMetrics(3, 8, true);
        metrics.recordCycleStart(1, 1, 10, 4, 11, 23, 9, 1, 900_000L, 100.0);
        metrics.recordCycleStart(2, 2, 20, 4, 11, 23, 8, 2, 910_000L, 200.0);
        metrics.recordBatchComplete(1, 1, 11, 23, 9, 1, 900_000L, 10.0, 100.0);
        metrics.recordBatchComplete(2, 2, 11, 23, 8, 2, 910_000L, 10.0, 200.0);
        metrics.recordIdle(1, 1, 1, 2, 900_000L, 216.0);
        metrics.recordIdle(2, 2, 1, 2, 910_000L, 216.0);
        metrics.recordExec(1, 1, 1, 0, 900_000L, 216.0);
        metrics.recordContentionStaleness(
                1, 1, 900_000L, 900_000L, 1, 1, 0, 0, 1, 5_000L, 10, 5, 15, 1, 4, 9, 23, 1, false, 0, 109L, 108.0);
        metrics.recordContentionStaleness(
                2, 2, 910_000L, 910_000L, 2, 2, 0, 0, 2, 15_000L, 18, 7, 25, 1, 8, 8, 23, 2, true, 1, 109L, 107.0);

        CoreIterationResult core = HighSpeedMetricsStatistics.calculate(0, 3, metrics);
        SystemIterationResult system = HighSpeedMetricsStatistics.calculateSystem(0, List.of(metrics));
        IterationResult iteration = new IterationResult(0, system, List.of(core));
        SystemForkResult fork = HighSpeedMetricsStatistics.calculateSystemFork(0, List.of(List.of(metrics)));
        ForkCalculationResult result = new ForkCalculationResult(fork, List.of(iteration));
        List<TrajectoryWindow> windows =
                List.of(new TrajectoryWindow(42L, 0, 10_000L, 5_000L, 1_000L, 200_000_000.0, true));

        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");
        TrialExport.exportTrajectoryTsv(
                first, CalibrationLifecycleMode.CONTINUOUS, windows, result, List.of(List.of(metrics)));
        TrialExport.exportTrajectoryTsv(
                second, CalibrationLifecycleMode.CONTINUOUS, windows, result, List.of(List.of(metrics)));

        assertArrayEquals(
                Files.readAllBytes(first.resolve(Constants.TRAJECTORY_WINDOWS_TSV)),
                Files.readAllBytes(second.resolve(Constants.TRAJECTORY_WINDOWS_TSV)));
        assertArrayEquals(
                Files.readAllBytes(first.resolve(Constants.TRAJECTORY_OCCUPANCY_TSV)),
                Files.readAllBytes(second.resolve(Constants.TRAJECTORY_OCCUPANCY_TSV)));
        assertEquals(
                Files.readString(first.resolve(Constants.TRAJECTORY_WINDOWS_CHECKSUM)),
                Files.readString(second.resolve(Constants.TRAJECTORY_WINDOWS_CHECKSUM)));
        assertTrue(Files.readString(first.resolve(Constants.TRAJECTORY_WINDOWS_TSV))
                .contains("42\tCONTINUOUS\t0\t10000\t5000\t1000\t2.0E8\ttrue"));
        String trajectory = Files.readString(first.resolve(Constants.TRAJECTORY_WINDOWS_TSV));
        assertTrue(trajectory.contains(
                "ordinaryIdleSelectedFraction\tproductivityExclusions\tproductivityExcludedFraction"));
        assertTrue(trajectory.contains("\t1\t0.5\t"));
        assertEquals(
                21,
                Files.readAllLines(first.resolve(Constants.TRAJECTORY_OCCUPANCY_TSV))
                        .size());
    }
}
