package io.euhedral_execution.hardware_utils.linux;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.euhedral_execution.hardware_utils.internal.topology.CoreKind;
import io.euhedral_execution.hardware_utils.internal.topology.LogicalCpu;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyInput;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LinuxSystemLayoutFixtureTest {

    private static LogicalCpu cpu(int cpu, int socket, int die, int core) {
        return new LogicalCpu(cpu, "linux:package:" + socket, "linux:die:" + die,
                "linux:core:" + core, CoreKind.UNKNOWN);
    }

    @Test
    void normalizesSparseMultisocketTopology() {
        List<LogicalCpu> values = new ArrayList<>(List.of(
                cpu(16, 1, 0, 0), cpu(2, 0, 0, 0), cpu(10, 0, 1, 0),
                cpu(0, 0, 0, 0), cpu(8, 0, 1, 0)));
        LinuxSystemLayout layout = new LinuxSystemLayout(
                () -> new TopologyInput("linux", values, List.of()));

        assertArrayEquals(new Integer[]{0, 2, 8, 10, 16},
                layout.getCpuInfoMap().keySet().toArray(Integer[]::new));
        assertEquals(3, layout.getCoreInfoMap().size());
        assertEquals(2, layout.getSocketInfoMap().size());
        assertNotEquals(layout.getCpuInfoMap().get(0).core(),
                layout.getCpuInfoMap().get(8).core());
        assertNotEquals(layout.getCpuInfoMap().get(8).core(),
                layout.getCpuInfoMap().get(16).core());
        assertNull(layout.getCpuInfoMap().get(1));
        for (int cpu : List.of(0, 2, 8, 10, 16)) {
            assertNotNull(layout.getCacheLayout().get(cpu));
        }
    }
}
