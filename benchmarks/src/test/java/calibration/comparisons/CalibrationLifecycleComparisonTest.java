package calibration.comparisons;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.comparisons.schema.CompatibilityStatus;
import calibration.comparisons.schema.CompletedRun;
import calibration.comparisons.schema.DifferenceCategory;
import calibration.comparisons.schema.RunArtifacts;
import calibration.comparisons.schema.RunIdentity;
import calibration.comparisons.schema.ThroughputResult;
import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.CalibrationLifecycleMode;
import calibration.config.TrialConfig;
import calibration.statistics.fork.SystemForkResult;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalibrationLifecycleComparisonTest {

    @Test
    void sameLifecycleModesAreCompatibleAndMixedModesAreIncompatible() {
        TrialConfig reset = trial("reset", CalibrationLifecycleMode.RESET);
        TrialConfig continuous = trial("continuous", CalibrationLifecycleMode.CONTINUOUS);

        assertEquals(
                CompatibilityStatus.COMPATIBLE,
                ComparisonCompatibilityAnalyzer.analyze(
                                run(reset), run(trial("reset-2", CalibrationLifecycleMode.RESET)))
                        .status());
        assertEquals(
                CompatibilityStatus.COMPATIBLE,
                ComparisonCompatibilityAnalyzer.analyze(
                                run(continuous), run(trial("continuous-2", CalibrationLifecycleMode.CONTINUOUS)))
                        .status());

        var mixed = ComparisonCompatibilityAnalyzer.analyze(run(reset), run(continuous));
        assertEquals(CompatibilityStatus.INCOMPATIBLE, mixed.status());
        assertFalse(mixed.isComparable());
        assertTrue(mixed.differences().stream()
                .anyMatch(difference -> difference.path().equals("/calibrationConfig/lifecycleMode")
                        && difference.category() == DifferenceCategory.LIFECYCLE));
    }

    private static TrialConfig trial(String id, CalibrationLifecycleMode lifecycleMode) {
        CalibrationBenchmarkConfig calibration = new CalibrationBenchmarkConfig(
                        List.of(2, 4, 6, 8),
                        2,
                        0,
                        0,
                        false,
                        1_000L,
                        10_000L,
                        null,
                        FragmentDecisionWeights.DEFAULT,
                        1024,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        0,
                        List.of(),
                        false)
                .withLifecycleMode(lifecycleMode);
        return new TrialConfig(id, id, "lifecycle", null, null, null, null, true, 2, 1, 3, null, calibration);
    }

    private static CompletedRun run(TrialConfig trial) {
        RunIdentity identity = new RunIdentity(trial.id(), trial.name(), trial.group(), 0, null, "/tmp/" + trial.id());
        return new CompletedRun(
                identity,
                trial,
                ThroughputResult.of(1_000.0, 10.0, "ops/s"),
                SystemForkResult.EMPTY,
                List.of(),
                RunArtifacts.standard("/tmp/" + trial.id()));
    }
}
