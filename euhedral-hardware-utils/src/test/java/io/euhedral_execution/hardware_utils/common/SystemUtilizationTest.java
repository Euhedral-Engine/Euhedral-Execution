package io.euhedral_execution.hardware_utils.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CpuSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SocketSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class SystemUtilizationTest {

    private static HardwareUtilization utilization() {
        SystemSnapshot snapshot =
                SystemSnapshot.create(10, 4, 2, 100, 0, 0, bits(0, 1, 3), new double[4], new long[] {1_000, 400, 0}, 0);
        return HardwareUtilization.create(
                10,
                2,
                0.5,
                100,
                bits(0, 1, 3),
                0.1,
                new double[] {0.1, 0.2, 0.0, 0.4},
                new double[] {0.2, 0.3, 0.0, 0.5},
                1_000,
                100,
                0.4,
                50,
                200,
                0.25,
                snapshot);
    }

    private static UnmodifiableBitSet bits(int... indexes) {
        BitSet set = new BitSet();
        for (int index : indexes) {
            set.set(index);
        }
        return UnmodifiableBitSet.wrap(set);
    }

    @Test
    void systemSnapshotValidatesTheMemoryTuple() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SystemSnapshot.create(1, 2, 1, 100, 0, 0, bits(0), new double[2], new long[2], 0));

        SystemSnapshot snapshot = SystemSnapshot.create(
                1, 2, 1, 100, 10, 20, bits(0, 1), new double[] {0.1, 0.2}, new long[] {1_000, 600, 100}, 30);

        assertEquals(1_000, snapshot.memoryLimit());
        assertEquals(600, snapshot.memoryUsage());
        assertEquals(100, snapshot.inactiveFileMemory());
        assertEquals(0.2, snapshot.pressurePerCpu().get(1));
    }

    @Test
    void derivesCpuCoreAndSocketSnapshotsFromOneUtilizationSample() {
        HardwareUtilization utilization = utilization();

        CpuSnapshot cpu = utilization.getCpuSnapshot(1, 0.5, 2);
        assertEquals(1, cpu.cpuId());
        assertEquals(0.3, cpu.stallRatio());
        assertEquals(0.2, cpu.throttleRatio());
        assertEquals(0.3, cpu.pressure(), 0.000_001);
        assertEquals(0.5, cpu.memoryUtilization());

        CoreSnapshot core = utilization.getCoreSnapshot(7, bits(1, 3), 1.5);
        assertEquals(7, core.coreId());
        assertEquals(3, core.globalCpuCount());
        assertEquals(200, core.memoryLimit());
        assertEquals(0.5, core.memoryUtilization());
        assertEquals(4, core.cpuSnapshots().length);

        SocketSnapshot socket = utilization.getSocketSnapshot(2, Arrays.asList(bits(0), null, bits(1, 3)), 2.0);
        assertEquals(2, socket.socketId());
        assertEquals(bits(0, 2), socket.effectiveCores());
        assertEquals(400, socket.globalBytesUsed());
        assertNull(utilization.getSocketSnapshot(0, List.of(), 1));
        assertNull(utilization.getSocketSnapshot(0, List.of(bits(0)), -1));

        CpuSnapshot absent = utilization.getCpuSnapshot(10, 0.5, 1);
        assertEquals(0, absent.pressure());
        assertEquals(10, absent.lastUsageNs());
        assertEquals(0.5, utilization.pressure());
        assertTrue(socket.memoryUtilization() >= 0);
    }
}
