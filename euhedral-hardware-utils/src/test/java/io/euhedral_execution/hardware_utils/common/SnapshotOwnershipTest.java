package io.euhedral_execution.hardware_utils.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CpuSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SocketSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

class SnapshotOwnershipTest {

    private static UnmodifiableBitSet bits(int... indexes) {
        BitSet bits = new BitSet();
        for (int index : indexes) {
            bits.set(index);
        }
        return new UnmodifiableBitSet(bits);
    }

    @Test
    void publishedSnapshotsRemainStableAndValueConsistent() {
        BitSet source = new BitSet();
        source.set(1);
        double[] pressures = {0, 0.5};
        SystemSnapshot snapshot = SystemSnapshot.create(1, 2, 1, 1, 1, 1,
                new UnmodifiableBitSet(source), pressures, new long[]{10, 5, 0}, 0);
        source.set(0);
        pressures[1] = 9;
        assertEquals(bits(1), snapshot.effectiveCpus());
        assertEquals(0.5, snapshot.pressurePerCpu().get(1));

        CpuSnapshot cpu = new CpuSnapshot(1, 1, 1, 1, 10, 5, 2, 0.5, 0.1, 0.2, 0.3, 4);
        CpuSnapshot[] cpuArray = {null, cpu};
        CoreSnapshot core = new CoreSnapshot(2, 1, 1, 1, 10, 5, 2, 0.5,
                bits(1), cpuArray);
        cpuArray[1] = null;
        CoreSnapshot equalCore = new CoreSnapshot(2, 1, 1, 1, 10, 5, 2, 0.5,
                bits(1), new CpuSnapshot[]{null, cpu});
        assertEquals(equalCore, core);
        assertEquals(equalCore.hashCode(), core.hashCode());
        CpuSnapshot[] accessor = core.cpuSnapshots();
        accessor[1] = null;
        assertEquals(cpu, core.cpuSnapshots()[1]);

        CoreSnapshot[] cores = {null, null, core};
        SocketSnapshot socket = new SocketSnapshot(0, bits(2), 10, 5, 2, 0.5, cores, 4);
        cores[2] = null;
        CoreSnapshot[] socketAccessor = socket.coreSnapshots();
        socketAccessor[2] = null;
        assertEquals(core, socket.coreSnapshots()[2]);
        assertNull(socket.coreSnapshots()[0]);
        assertNotEquals(socket, new SocketSnapshot(0, bits(2), 10, 6, 2, 0.5,
                new CoreSnapshot[]{null, null, core}, 4));

        assertThrows(NullPointerException.class, () -> new CoreSnapshot(0, 0, 0, 0,
                0, 0, 0, 0, null, new CpuSnapshot[0]));
    }

    @Test
    void validatesAndSanitizesRatioFields() {
        double[] pressures = {Double.NaN, -0.5, 1.5, -0.0};
        SystemSnapshot snapshot = SystemSnapshot.create(1, 4, 1, 1, -10, -5,
                bits(0, 1, 2, 3), pressures, new long[]{-10, -5, -1}, -20);

        assertEquals(0, snapshot.cpuUsage());
        assertEquals(0, snapshot.cpuThrottle());
        assertEquals(0, snapshot.memoryUsage());
        assertEquals(0, snapshot.inactiveFileMemory());
        assertEquals(0, snapshot.diskIOBytes());
        assertEquals(Long.MAX_VALUE, snapshot.memoryLimit());

        assertEquals(0.0, snapshot.pressurePerCpu().get(0));
        assertEquals(0.0, snapshot.pressurePerCpu().get(1));
        assertEquals(1.0, snapshot.pressurePerCpu().get(2));
        assertEquals(0.0, snapshot.pressurePerCpu().get(3)); // -0.0 -> +0.0
        assertEquals(Double.doubleToRawLongBits(+0.0),
                Double.doubleToRawLongBits(snapshot.pressurePerCpu().get(3)));

        HardwareUtilization util = HardwareUtilization.create(
                1, 1, Double.NaN, 1, bits(0, 1, 2, 3), -0.0,
                pressures, pressures, -10, -5, 1.5, -2, -0.5, Double.NaN, snapshot);

        assertEquals(0.0, util.quotaCpuUsage());
        assertEquals(0.0, util.cpuThrottleRatio());
        assertEquals(1.0, util.totalMemoryUtilization());
        assertEquals(0.0, util.diskIOPressure());
        assertEquals(0.0, util.diskIOBytesPerSecond());
        assertEquals(0, util.globalMemoryPool());
        assertEquals(0, util.perCpuMemoryPool());
        assertEquals(0, util.memPerCpuUsageBytes());
        assertEquals(0.0, util.perQuotaCpuThrottleRatio().get(0));
        assertEquals(1.0, util.perQuotaCpuPressure().get(2));
    }
}
