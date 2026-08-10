package io.euhedral_execution.hardware_utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSystemTopology;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

class TopologyMapperTest {

    private static BitSet expectedEffectiveCpus(BitSet allowed) {
        BitSet expected = (BitSet) allowed.clone();
        SystemInfo.CoreInfo coreZero = SystemInfo.getCoreInfo(0);
        if (coreZero != null) {
            BitSet coreZeroCpus = coreZero.getCpuSet();
            expected.andNot(coreZeroCpus);
            if (expected.isEmpty()) {
                expected.or(coreZeroCpus);
            }
        }
        expected.and(allowed);
        return expected;
    }

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
    void publishesOnlyChangedEffectiveTopology() {
        BitSet allowed = (BitSet) SystemInfo.getCpuSet().clone();
        assertTrue(allowed.cardinality() > 0, "system topology contains no CPUs");
        TopologyMapper mapper = new TopologyMapper((BitSet) allowed.clone());
        HardwareUtilization utilization = utilization(allowed);

        mapper.update(utilization);

        EffectiveSystemTopology topology = mapper.getEffectiveTopology();
        BitSet expectedCpus = expectedEffectiveCpus(allowed);
        assertEquals(expectedCpus, topology.effectiveCpus());
        assertEquals(1, topology.globalVersion());
        assertEquals(1, mapper.getGlobalVersion());
        assertThrows(RuntimeException.class, () -> topology.effectiveCpus().clear());

        for (int socket = topology.effectiveSockets().nextSetBit(0);
                socket >= 0;
                socket = topology.effectiveSockets().nextSetBit(socket + 1)) {
            EffectiveSocketTopology socketTopology = mapper.getEffectiveSocketTopology(socket);
            assertNotNull(socketTopology);
            assertTrue(expectedCpus.intersects(socketTopology.effectiveCpus()));
        }

        mapper.update(utilization);
        assertEquals(1, mapper.getGlobalVersion());
        assertEquals(topology, mapper.getEffectiveTopology());
    }
}
