package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.topology.SystemInfo;
import io.euhedral_execution.hardware_utils.topology.TopologyMapper;
import io.euhedral_execution.hardware_utils.util.UnmodifiableBitSet;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

class CoreZeroReservationCompatibilityTest {

    private static HardwareUtilization utilization(BitSet effectiveCpus) {
        int cpuCount = Math.max(SystemInfo.getCpuCount(), effectiveCpus.length());
        SystemSnapshot snapshot = SystemSnapshot.create(
                1,
                cpuCount,
                effectiveCpus.cardinality(),
                100_000,
                0,
                0,
                UnmodifiableBitSet.wrap((BitSet) effectiveCpus.clone()),
                new double[cpuCount],
                new long[] {1_000, 0, 0},
                0);
        return HardwareUtilization.create(
                1,
                effectiveCpus.cardinality(),
                0,
                100_000,
                UnmodifiableBitSet.wrap((BitSet) effectiveCpus.clone()),
                0,
                new double[cpuCount],
                new double[cpuCount],
                1_000,
                Math.max(1, 1_000 / Math.max(cpuCount, 1)),
                0,
                0,
                0,
                0,
                snapshot);
    }

    @Test
    void retainsCoreZeroWhenItIsAllowedAndAvailable() {
        BitSet allowed = SystemInfo.getCpuSet();
        assertFalse(allowed.isEmpty(), "system topology contains no CPUs");
        TopologyMapper mapper = new TopologyMapper((BitSet) allowed.clone());
        mapper.update(utilization(allowed));

        assertEquals(allowed, mapper.getEffectiveTopology().effectiveCpus());
    }
}
