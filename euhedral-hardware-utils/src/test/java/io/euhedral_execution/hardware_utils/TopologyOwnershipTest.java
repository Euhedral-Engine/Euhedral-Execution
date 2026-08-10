package io.euhedral_execution.hardware_utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.euhedral_execution.hardware_utils.internal.topology.CacheDomain;
import io.euhedral_execution.hardware_utils.internal.topology.CoreKind;
import io.euhedral_execution.hardware_utils.internal.topology.LogicalCpu;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyInput;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyModel;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyNormalizer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopologyOwnershipTest {

    @Test
    void doesNotAliasProviderStorage() {
        List<LogicalCpu> cpus = new ArrayList<>(List.of(
                new LogicalCpu(0, "fallback:package:0", "fallback:die:0", "fallback:core:00000000", CoreKind.UNKNOWN)));
        BitSet mask = new BitSet();
        mask.set(0);
        CacheDomain domain = new CacheDomain(1, 12345, 64, mask);
        TopologyInput input = new TopologyInput("fallback", cpus, List.of(domain));
        cpus.clear();
        mask.clear();
        domain.logicalCpuSharers().clear();
        TopologyModel model = new TopologyNormalizer().normalize(input);

        assertEquals(12345, model.cacheLayout().get(0).bytesL1());
        int[] ids = model.activeLogicalIds();
        ids[0] = 99;
        assertEquals(0, model.activeLogicalIds()[0]);
        assertThrows(
                UnsupportedOperationException.class,
                () -> model.cpuInfo().put(1, model.cpuInfo().get(0)));
    }
}
