package io.euhedral_execution.hardware_utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.euhedral_execution.hardware_utils.internal.topology.CacheDomain;
import io.euhedral_execution.hardware_utils.internal.topology.CoreKind;
import io.euhedral_execution.hardware_utils.internal.topology.LogicalCpu;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyInput;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyModel;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyNormalizer;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopologyCacheFallbackTest {

    private static LogicalCpu cpu(int id, int socket, int core) {
        return new LogicalCpu(id, "linux:package:" + socket, "linux:die:0",
                "linux:core:" + core, CoreKind.UNKNOWN);
    }

    private static BitSet bits(int... ids) {
        BitSet value = new BitSet();
        for (int id : ids) {
            value.set(id);
        }
        return value;
    }

    @Test
    void completesEveryActiveCpuDeterministically() {
        TopologyInput input = new TopologyInput("linux", List.of(
                cpu(0, 0, 0), cpu(2, 0, 0), cpu(8, 1, 1)), List.of(
                new CacheDomain(1, 64 * 1024, 128, bits(0, 2)),
                new CacheDomain(3, 9 * 1024 * 1024L, 64, bits(0, 8))));
        TopologyModel model = new TopologyNormalizer().normalize(input);

        assertEquals(64 * 1024, model.cacheLayout().get(0).bytesL1());
        assertEquals(SystemInfo.DEFAULT_L2, model.cacheLayout().get(2).bytesL2());
        assertEquals(SystemInfo.DEFAULT_L3, model.cacheLayout().get(0).bytesL3());
        assertEquals(SystemInfo.DEFAULT_L3, model.cacheLayout().get(8).bytesL3());
        assertEquals(128, model.cacheLineBytes());
    }
}
