package calibration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.CalibrationLifecycleMode;
import calibration.config.ComparisonConfig;
import calibration.config.ComparisonStrategy;
import calibration.config.HarnessConfig;
import calibration.config.TrialConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CalibrationLifecyclePresetTest {

    @Test
    void lifecyclePresetIsAnOtherwiseIdenticalPolicyByLifecycleTwoByTwo() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        HarnessConfig harness =
                CalibrationRunner.loadConfig("src/main/presets/experiments/02-productivity-lifecycle-2x2.json", mapper);
        List<TrialConfig> trials = CalibrationRunner.resolveTrials(harness, mapper);
        Map<String, TrialConfig> byId = trials.stream().collect(Collectors.toMap(TrialConfig::id, Function.identity()));

        assertEquals(4, trials.size());
        assertAxes(byId.get("reset-policy-off"), CalibrationLifecycleMode.RESET, 0);
        assertAxes(byId.get("reset-policy-on"), CalibrationLifecycleMode.RESET, 216);
        assertAxes(byId.get("continuous-policy-off"), CalibrationLifecycleMode.CONTINUOUS, 0);
        assertAxes(byId.get("continuous-policy-on"), CalibrationLifecycleMode.CONTINUOUS, 216);

        CalibrationBenchmarkConfig fixture = trials.getFirst().calibrationConfig();
        for (TrialConfig trial : trials) {
            CalibrationBenchmarkConfig config = trial.calibrationConfig();
            assertEquals(fixture.cpuSet(), config.cpuSet());
            assertEquals(30, config.cpuSet().size());
            assertEquals(2, config.cpuSet().getFirst());
            assertEquals(31, config.cpuSet().getLast());
            assertEquals(11, config.parallelSources());
            assertEquals(0, config.orderedSources());
            assertEquals(0, config.workUnits());
            assertEquals(false, config.randomizeWork());
            assertEquals(8_000_000L, config.totalRequiredExecutions());
            assertEquals(1024, config.rawSampleLimit());
            assertTrue(config.observeCycleStart());
            assertTrue(config.observeBatchComplete());
            assertTrue(config.observeIdleDecision());
            assertTrue(config.observeExecDecision());
            assertTrue(config.observeContentionStaleness());
            assertEquals(3, trial.forks());
            assertEquals(6, trial.iterations());
            assertEquals("5s", trial.measurementTime());
        }

        ComparisonConfig comparison = mapper.readValue(
                new File("src/main/presets/comparisons/02-productivity-lifecycle-2x2.json"), ComparisonConfig.class);
        assertEquals(ComparisonStrategy.KEYED, comparison.strategy());
        assertEquals(
                List.of("/calibrationConfig/lifecycleMode"), comparison.key().paths());
        assertEquals(2, comparison.baseline().runs().size());
        assertEquals(2, comparison.candidate().runs().size());
    }

    private static void assertAxes(TrialConfig trial, CalibrationLifecycleMode mode, int threshold) {
        assertEquals(mode, trial.calibrationConfig().lifecycleMode());
        assertEquals(threshold, trial.calibrationConfig().productivityThresholdWeight());
    }
}
