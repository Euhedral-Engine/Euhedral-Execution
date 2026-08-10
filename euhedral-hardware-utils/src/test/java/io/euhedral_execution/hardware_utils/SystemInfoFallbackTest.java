package io.euhedral_execution.hardware_utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.internal.topology.TopologyBootstrap;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyInput;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyModel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class SystemInfoFallbackTest {

    @Test
    void initializesWithIncompletePlatformTopology() {
        TopologyModel model = TopologyBootstrap.normalize(
                () -> new TopologyInput("macos", List.of(), List.of()),
                4,
                LoggerFactory.getLogger(getClass()),
                "fixture");

        assertEquals(4, model.cpuCount());
        assertEquals(4, model.coreCount());
        assertEquals(1, model.socketCount());
        for (int cpu = 0; cpu < 4; cpu++) {
            assertNotNull(model.cpuInfo().get(cpu));
            SystemInfo.CpuCacheLayout cache = model.cacheLayout().get(cpu);
            assertNotNull(cache);
            assertEquals(1, cache.sharesL1());
            assertEquals(1, cache.sharesL2());
            assertEquals(4, cache.sharesL3());
            assertTrue(cache.getL3Mask().get(cpu));
        }
    }
}
