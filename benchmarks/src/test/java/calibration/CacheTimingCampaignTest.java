package calibration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import calibration.config.CalibrationBenchmarkConfig;
import calibration.config.HarnessConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.euhedral_execution.core.config.CacheTimingConfig;
import java.io.File;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class CacheTimingCampaignTest {
    @Test
    void retainedHarnessResolvesFrozenAndExplicitOffWithoutExecutingBenchmarks() throws Exception {
        var mapper = new ObjectMapper();
        var task = mapper.readTree(new File("../python/pareto-weight-calibration/tasks/cache-scarce-loop.json"));
        var frozen =
                mapper.readTree(new File("../python/pareto-weight-calibration/policies/cache-scarce-v1-runtime.json"));
        ObjectNode harness = task.path("benchmark").path("harness").deepCopy();
        ObjectNode template = harness.path("trials").get(0).deepCopy();
        var trials = harness.putArray("trials");
        for (String arm : new String[] {"FROZEN_POLICY", "POLICY_OFF"}) {
            ObjectNode trial = template.deepCopy();
            trial.put("id", arm);
            trial.putObject("labels").put("policyId", arm);
            ((ObjectNode) trial.get("origin")).put("candidateIndex", trials.size());
            ObjectNode config = (ObjectNode) trial.get("calibrationConfig");
            config.put("cacheScarcityGateEnabled", true);
            if (arm.equals("POLICY_OFF")) config.putNull("cacheTimingFunction");
            else config.set("cacheTimingFunction", frozen);
            trials.add(trial);
        }
        var resolved = CalibrationRunner.resolveTrials(mapper.treeToValue(harness, HarnessConfig.class), mapper);
        assertEquals(2, resolved.size());
        for (var trial : resolved) {
            assertEquals(1, trial.forks());
            var config = trial.calibrationConfig();
            if (config.cacheTimingFunction() == null) {
                assertEquals(15000, config.cacheParkNs());
                assertEquals(1000000, config.contentionHalfLifeNanos());
            } else assertEquals(CacheTimingConfig.DEFAULT_FUNCTION, config.cacheTimingFunction());
        }
        assertEquals(
                1,
                resolved.stream()
                        .filter(t -> t.calibrationConfig().cacheTimingFunction() == null)
                        .count());
    }

    @Test
    void liveHandoffResolvesAllControlsAndInteractionsInTwoReversedPasses() throws Exception {
        var mapper = new ObjectMapper();
        String path = System.getenv("EUHEDRAL_CACHE_TIMING_HANDOFF");
        if (path == null) path = "src/test/resources/cache-timing/live-v1";
        assumeTrue(
                new File(path, "policy_harness.json").isFile(),
                "optional historical handoff: set EUHEDRAL_CACHE_TIMING_HANDOFF");
        var config = mapper.readValue(new File(path, "policy_harness.json"), HarnessConfig.class);
        var trials = CalibrationRunner.resolveTrials(config, mapper);
        assertEquals(684, trials.size());
        var policyIds = new HashSet<String>();
        int fixed = 0;
        for (int i = 0; i < 342; i++) {
            var first = trials.get(i);
            var second = trials.get(683 - i);
            assertEquals(first.calibrationConfig(), second.calibrationConfig());
            assertEquals(0, first.origin().sampleIndex());
            assertEquals(1, second.origin().sampleIndex());
            assertEquals(1, first.forks());
            assertEquals(3, first.warmups());
            assertEquals(5, first.iterations());
            assertNull(first.calibrationConfig().forcedActiveParticipantCount());
            var function = first.calibrationConfig().cacheTimingFunction();
            if (function == null) fixed++;
            else {
                assertEquals(7, function.parkCoefficients().size());
                assertEquals(7, function.halfLifeCoefficients().size());
                assertEquals(15_000, function.parkMinNanos());
                assertEquals(814_375, function.parkMaxNanos());
                assertEquals(250_000, function.halfLifeMinNanos());
                assertEquals(2_000_000, function.halfLifeMaxNanos());
            }
            policyIds.add(first.labels().get("policyId"));
            assertEquals(
                    first.calibrationConfig(),
                    mapper.readValue(
                            mapper.writeValueAsString(first.calibrationConfig()), CalibrationBenchmarkConfig.class));
        }
        assertEquals(45, fixed);
        assertEquals(38, policyIds.size());
    }

    @Test
    void fixedHandoffExpandsToTwoReversedPassesOf144IndependentForks() throws Exception {
        var mapper = new ObjectMapper();
        String path = System.getenv("EUHEDRAL_CACHE_FIXED_HANDOFF");
        if (path == null) path = "src/test/resources/cache-timing";
        assumeTrue(
                new File(path, "fixed_harness.json").isFile(),
                "optional historical handoff: set EUHEDRAL_CACHE_FIXED_HANDOFF");
        var config = mapper.readValue(new File(path, "fixed_harness.json"), HarnessConfig.class);
        var trials = CalibrationRunner.resolveTrials(config, mapper);
        assertEquals(288, trials.size());
        for (int i = 0; i < 144; i++) {
            var first = trials.get(i);
            var second = trials.get(287 - i);
            assertEquals(first.calibrationConfig(), second.calibrationConfig());
            assertEquals(0, first.origin().sampleIndex());
            assertEquals(1, second.origin().sampleIndex());
            assertEquals(1, first.forks());
            assertEquals(5, first.iterations());
            assertNull(first.calibrationConfig().forcedActiveParticipantCount());
            assertNull(first.calibrationConfig().cacheTimingFunction());
            assertEquals(1_000_000, first.calibrationConfig().totalRequiredExecutions());
        }
    }
}
