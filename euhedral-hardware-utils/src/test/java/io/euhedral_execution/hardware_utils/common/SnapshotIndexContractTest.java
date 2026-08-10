package io.euhedral_execution.hardware_utils.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SocketSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import java.util.Arrays;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

class SnapshotIndexContractTest {

    private static HardwareUtilization utilization(
            long globalPool, long perCpuPool, double globalUtilization, long perCpuUsage) {
        BitSet cpus = bits(3, 7);
        double[] values = new double[8];
        SystemSnapshot snapshot = SystemSnapshot.create(
                23, 8, 2, 100, 0, 0, new UnmodifiableBitSet(cpus), values, new long[] {1, 0, 0}, 0);
        return HardwareUtilization.create(
                23,
                2,
                0,
                100,
                new UnmodifiableBitSet(cpus),
                0.2,
                values,
                values,
                globalPool,
                perCpuPool,
                globalUtilization,
                perCpuUsage,
                0,
                0.4,
                snapshot);
    }

    private static BitSet bits(int... indexes) {
        BitSet result = new BitSet();
        for (int index : indexes) {
            result.set(index);
        }
        return result;
    }

    @Test
    void populatesNamedFieldsAndActiveEntries() {
        HardwareUtilization utilization =
                utilization(Long.MAX_VALUE, Long.MAX_VALUE, Double.POSITIVE_INFINITY, Long.MAX_VALUE);
        SocketSnapshot socket = utilization.getSocketSnapshot(1, Arrays.asList(null, bits(3), null, bits(7)), 2);

        assertEquals(bits(1, 3), socket.effectiveCores());
        assertEquals(4, socket.coreSnapshots().length);
        assertNull(socket.coreSnapshots()[0]);
        assertEquals(8, socket.coreSnapshots()[3].cpuSnapshots().length);
        assertEquals(2, socket.coreSnapshots()[1].globalCpuCount());
        assertEquals(Long.MAX_VALUE, socket.globalMemoryLimit());
        assertEquals(0, socket.globalBytesUsed());
        assertEquals(Long.MAX_VALUE, socket.memoryLimit());
        assertTrue(Double.isFinite(socket.memoryUtilization()));
        assertEquals(23, socket.lastUsageNs());
        assertEquals(23, socket.coreSnapshots()[3].cpuSnapshots()[7].lastUsageNs());
        assertThrows(
                IllegalArgumentException.class,
                () -> utilization.getSocketSnapshot(0, Arrays.asList(bits(3), bits(3)), 1));
        assertThrows(IllegalArgumentException.class, () -> utilization.getSocketSnapshot(0, Arrays.asList(bits(4)), 1));

        HardwareUtilization withPressure = utilization(100, 10, 0.5, 5);
        assertEquals(0.0, withPressure.pressure(), 0.0001); // all zeros in values[]

        double[] newValues = new double[8];
        newValues[3] = 0.8;
        HardwareUtilization highPressure = HardwareUtilization.create(
                23,
                2,
                0,
                100,
                new UnmodifiableBitSet(bits(3, 7)),
                0.2,
                newValues,
                newValues,
                100,
                10,
                0.5,
                5,
                0,
                0.4,
                withPressure.snapshot());
        assertEquals(0.8, highPressure.pressure(), 0.0001);
    }
}
