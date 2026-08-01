package io.euhedral_execution.hardware_utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSystemTopology;
import org.junit.jupiter.api.Test;

class TopologyMapperVersionTest {

    @Test
    void versionsOnlyPublishedMembershipChanges() {
        TopologyMapper mapper = new TopologyMapper(TopologyHelpers.twoSocketModel(),
                TopologyHelpers.bits(0, 3, 7));
        assertEquals(-1, mapper.getGlobalVersion());

        mapper.update(TopologyHelpers.utilization(TopologyHelpers.bits(0, 3, 7)));
        EffectiveSystemTopology both = mapper.getEffectiveTopology();
        assertEquals(1, both.globalVersion());
        assertEquals(1, both.socketTopologies().get(0).version());
        assertEquals(1, both.socketTopologies().get(1).version());
        mapper.update(TopologyHelpers.utilization(TopologyHelpers.bits(0, 3, 7)));
        assertSame(both, mapper.getEffectiveTopology());

        mapper.update(TopologyHelpers.utilization(TopologyHelpers.bits(0, 3)));
        EffectiveSystemTopology one = mapper.getEffectiveTopology();
        assertEquals(2, one.globalVersion());
        assertNull(one.socketTopologies().get(1));
        assertEquals(1, one.socketTopologies().get(0).version());

        mapper.update(TopologyHelpers.utilization(TopologyHelpers.bits(0, 3, 7)));
        EffectiveSystemTopology reactivated = mapper.getEffectiveTopology();
        assertEquals(3, reactivated.globalVersion());
        assertEquals(3, reactivated.socketTopologies().get(1).version());
        assertEquals(1, reactivated.socketTopologies().get(0).version());
    }
}
