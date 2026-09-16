package io.euhedral_execution.benchmarks.cfd.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import io.euhedral_execution.hardware_utils.SystemInfo;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BackendOptionsTest {
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "1.5", "true", "\"1\"", "\"random\"", "2147483648"})
    void rejectsInvalidSourceCounts(String sources) {
        assertThrows(
                Exception.class,
                () -> ConfigLoader.load(
                        Path.of("scenes/periodic-smoke.json"),
                        List.of(
                                "execution.backendOptions.backend=\"euhedral\"",
                                "execution.backendOptions.sources=" + sources)));
    }

    @Test
    void sourcesResolveOnceAgainstEffectiveCpuWorkersAndReplayPreservesTheSetting() throws Exception {
        var config = ConfigLoader.load(
                Path.of("scenes/periodic-smoke.json"),
                List.of("execution.backendOptions.backend=\"euhedral\"", "execution.backendOptions.workers=2"));
        var options = config.config().execution().backendOptions();
        var budget = WorkerBudget.resolve(options);
        assertTrue(budget.workerCount() <= 2);
        assertEquals(budget.workerCount(), options.sourceCount(budget.workerCount()));
        for (int cpu : budget.effectiveCpus()) {
            assertTrue(SystemInfo.getCpuSet().get(cpu));
        }
        long availableCpus = SystemInfo.getCpuSet().stream()
                .filter(io.euhedral_execution.hardware_utils.ThreadTools.BASE_MASK::get)
                .count();
        if (availableCpus >= 2) {
            assertEquals(2, budget.workerCount());
        }
        assertEquals(
                java.util.Arrays.stream(budget.effectiveCpus())
                        .map(cpu -> SystemInfo.getCpuInfo(cpu).core())
                        .distinct()
                        .count(),
                budget.physicalCoreCount());
        assertTrue(ConfigLoader.replayJson(config).contains("\"sources\" : \"workers\""));
        assertEquals(1, new BackendOptions("euhedral", 2, 1, null, false, null).sourceCount(2));
        assertEquals(5, new BackendOptions("euhedral", 2, 5, null, false, null).sourceCount(2));
        assertThrows(IllegalArgumentException.class, () -> new BackendOptions("serial", 2, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new BackendOptions("static", 2, 1, null, null, null));
    }
}
