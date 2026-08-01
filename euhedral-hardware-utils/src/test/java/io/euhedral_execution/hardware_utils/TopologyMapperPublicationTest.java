package io.euhedral_execution.hardware_utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSystemTopology;
import java.util.BitSet;
import org.junit.jupiter.api.Test;

class TopologyMapperPublicationTest {

    @Test
    void publishesOwnedCoalescedTopology() throws Exception {
        TopologyMapper mapper = new TopologyMapper(TopologyHelpers.twoSocketModel(),
                TopologyHelpers.bits(0, 3, 7, 100));
        Thread first = new Thread(() -> mapper.update(TopologyHelpers.utilization(
                TopologyHelpers.bits(0, 3))));
        Thread second = new Thread(() -> mapper.update(TopologyHelpers.utilization(
                TopologyHelpers.bits(0, 3, 7, 100))));
        first.start();
        second.start();
        first.join(5_000);
        second.join(5_000);
        mapper.update(TopologyHelpers.utilization(TopologyHelpers.bits(0, 3, 7, 100)));

        EffectiveSystemTopology topology = mapper.getEffectiveTopology();
        assertEquals(TopologyHelpers.bits(3, 7), topology.effectiveCpus());
        assertEquals(2, topology.socketTopologies().size());
        for (int socket = 0; socket < 2; socket++) {
            EffectiveSocketTopology entry = topology.socketTopologies().get(socket);
            assertEquals(3, entry.effectiveCoreToCpu().size());
        }
        assertNull(topology.socketTopologies().get(0).effectiveCoreToCpu().get(0));
        assertThrows(RuntimeException.class, () -> topology.effectiveCpus().set(0));

        BitSet directMask = TopologyHelpers.bits(3);
        EffectiveSystemTopology direct = new EffectiveSystemTopology(new BitSet(), new BitSet(),
                directMask, topology.socketTopologies(), 1);
        directMask.clear();
        assertEquals(TopologyHelpers.bits(3), direct.effectiveCpus());

        EffectiveSystemTopology sparse = new EffectiveSystemTopology(new BitSet(), new BitSet(),
                new BitSet(), java.util.Collections.singletonList(null), 1);
        assertNull(sparse.socketTopologies().get(0));
    }
}
