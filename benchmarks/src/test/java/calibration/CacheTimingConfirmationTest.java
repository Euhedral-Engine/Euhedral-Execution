package calibration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import calibration.config.HarnessConfig;
import calibration.config.ParticipationDynamicScenario;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.config.CacheTimingFunctionConfig;
import io.euhedral_execution.hardware_utils.SystemInfo;
import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

class CacheTimingConfirmationTest {
    @Test
    void boundedStagesResolveExactFinalistsPhysicalWorkersAndPersistentPhaseSchedules() throws Exception {
        var mapper = new ObjectMapper();
        String path = System.getenv("EUHEDRAL_CACHE_CONFIRMATION_HANDOFF");
        if (path == null) path = "src/test/resources/cache-timing/confirmation-v2";
        assumeTrue(
                new File(path, "candidate_manifest.json").isFile(),
                "optional historical handoff: set EUHEDRAL_CACHE_CONFIRMATION_HANDOFF");
        var manifest = mapper.readTree(new File(path, "candidate_manifest.json"));
        String originalPath = System.getenv("EUHEDRAL_CACHE_CONFIRMATION_ORIGINAL");
        if (originalPath == null) originalPath = "src/test/resources/cache-timing/live-v2/candidate_manifest.json";
        var original = mapper.readTree(new File(originalPath));
        for (var stage : Map.of("r23", 72, "topologies", 144, "dynamic", 72).entrySet()) {
            var config = mapper.readValue(new File(path, stage.getKey() + "_harness.json"), HarnessConfig.class);
            var trials = CalibrationRunner.resolveTrials(config, mapper);
            assertEquals(stage.getValue(), trials.size());
            int blockSize = trials.size() / 4;
            for (int pair = 0; pair < 2; pair++) {
                for (int i = 0; i < blockSize; i++) {
                    var left = trials.get(pair * 2 * blockSize + i);
                    var right = trials.get((pair * 2 + 2) * blockSize - 1 - i);
                    assertEquals(left.calibrationConfig(), right.calibrationConfig());
                    assertEquals(left.jvmArgs(), right.jvmArgs());
                    assertEquals(pair * 2, left.origin().sampleIndex());
                    assertEquals(pair * 2 + 1, right.origin().sampleIndex());
                }
            }
            for (var trial : trials) {
                var cfg = trial.calibrationConfig();
                assertEquals(1, trial.forks());
                assertEquals(15000, cfg.cacheParkNs());
                assertEquals(1000000, cfg.contentionHalfLifeNanos());
                assertNull(cfg.forcedActiveParticipantCount());
                assertEquals("AUTO", cfg.productivityGateMode().name());
                assertEquals("CONTINUOUS", cfg.lifecycleMode().name());
                assertTrue(trial.jvmArgs().contains("-Deuhedral.calibration.throughputOnly=true"));
                String arm = trial.labels().get("policyId");
                if (arm.equals("POLICY_OFF")) assertNull(cfg.cacheTimingFunction());
                else {
                    var frozen = StreamSupport.stream(original.get("policies").spliterator(), false)
                            .filter(p -> p.get("id").asText().equals(arm))
                            .findFirst()
                            .orElseThrow();
                    assertEquals(
                            mapper.treeToValue(frozen.get("function"), CacheTimingFunctionConfig.class),
                            cfg.cacheTimingFunction());
                }
                var fixture = StreamSupport.stream(
                                manifest.get("stages")
                                        .get(stage.getKey())
                                        .get("fixtures")
                                        .spliterator(),
                                false)
                        .filter(f -> f.get("workloadId")
                                .asText()
                                .equals(trial.labels().get("workloadId")))
                        .findFirst()
                        .orElseThrow();
                var cores = new HashSet<String>();
                for (int cpu : cfg.cpuSet()) {
                    var info = SystemInfo.getCpuInfo(cpu);
                    cores.add(info.socket() + ":" + info.core());
                }
                assertEquals(fixture.get("resolvedWorkers").asInt(), cores.size());
                if (stage.getKey().equals("dynamic")) {
                    assertEquals(8, trial.iterations());
                    assertEquals(4, trial.warmups());
                    var scenario = ParticipationDynamicScenario.valueOf(
                            fixture.get("scenario").asText());
                    assertTrue(trial.jvmArgs()
                            .contains("-Deuhedral.calibration.participationDynamicScenario=" + scenario.name()));
                    for (int i = 0; i < 8; i++) {
                        var phase = scenario.phase(i, 8);
                        var expected = fixture.get("phases").get(i / 4);
                        assertEquals(expected.get("workUnits").asInt(), phase.workUnits());
                        assertEquals(
                                expected.get("enabledSources").asInt(),
                                phase.enabledSources() < 0 ? cfg.parallelSources() : phase.enabledSources());
                    }
                } else {
                    assertEquals(5, trial.iterations());
                    assertEquals(3, trial.warmups());
                    assertEquals(
                            trial.labels().get("workloadClass").equals("PRIMARY") ? 1 : cores.size(),
                            cfg.parallelSources());
                }
            }
        }
    }
}
