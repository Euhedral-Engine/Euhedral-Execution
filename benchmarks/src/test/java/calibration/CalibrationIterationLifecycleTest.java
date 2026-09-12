package calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calibration.config.CalibrationLifecycleMode;
import calibration.statistics.iteration.TrajectoryWindow;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalibrationIterationLifecycleTest {

    @Test
    void resetModePreservesPhysicalResetBeforeAndAfterEveryWindow() {
        List<String> operations = new ArrayList<>();
        CalibrationIterationLifecycle lifecycle = new CalibrationIterationLifecycle(CalibrationLifecycleMode.RESET);

        lifecycle.beforeWindow(() -> operations.add("physical-reset"), () -> operations.add("observe-start"));
        operations.add("observe-stop");
        lifecycle.afterWindow(() -> operations.add("physical-reset"));

        assertEquals(List.of("physical-reset", "observe-start", "observe-stop", "physical-reset"), operations);
    }

    @Test
    void continuousWindowTransitionOnlySegmentsMeasurementState() {
        List<String> operations = new ArrayList<>();
        CalibrationIterationLifecycle lifecycle =
                new CalibrationIterationLifecycle(CalibrationLifecycleMode.CONTINUOUS);

        lifecycle.beforeWindow(() -> operations.add("physical-reset"), () -> operations.add("observe-start"));
        operations.add("observe-stop");
        lifecycle.afterWindow(() -> operations.add("physical-reset"));
        lifecycle.beforeWindow(() -> operations.add("physical-reset"), () -> operations.add("observe-start"));

        assertEquals(List.of("observe-start", "observe-stop", "observe-start"), operations);
    }

    @Test
    void continuousCountersAreSegmentedFromOneMonotonicTrajectory() {
        TrajectoryWindow first =
                CalibrationBenchmark.createTrajectoryWindow(42L, 0, 1_000L, 2_000L, 3_000L, 10_000L, 12_000L, 1_000L);
        TrajectoryWindow second =
                CalibrationBenchmark.createTrajectoryWindow(42L, 1, 1_000L, 4_000L, 6_000L, 15_000L, 19_000L, 1_000L);

        assertEquals(2_000L, first.completedExecutions());
        assertEquals(4_000L, second.completedExecutions());
        assertEquals(2_000_000_000.0, first.throughputExecutionsPerSecond());
        assertEquals(2_000_000_000.0, second.throughputExecutionsPerSecond());
        assertEquals(5_000L, second.trajectoryElapsedNanos());
    }

    @Test
    void continuousWindowFailsWhenSourceDidNotRemainFed() {
        assertThrows(
                IllegalStateException.class,
                () -> CalibrationBenchmark.createTrajectoryWindow(
                        42L, 0, 1_000L, 2_000L, 3_000L, 10_000L, 10_999L, 1_000L));
    }
}
