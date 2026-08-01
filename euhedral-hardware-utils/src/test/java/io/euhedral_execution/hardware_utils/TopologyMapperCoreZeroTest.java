package io.euhedral_execution.hardware_utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import org.junit.jupiter.api.Test;

class TopologyMapperCoreZeroTest {

    @Test
    void fallsBackWhenCoreZeroIsTheOnlyAllowedCore() {
        BitSet allowed = TopologyHelpers.bits(5, 99);
        TopologyMapper mapper = new TopologyMapper(TopologyHelpers.coreZeroModel(), allowed);
        allowed.clear();
        mapper.update(TopologyHelpers.utilization(TopologyHelpers.bits(5, 99)));

        assertEquals(TopologyHelpers.bits(5), mapper.getEffectiveTopology().effectiveCpus());
        mapper.update(TopologyHelpers.utilization(TopologyHelpers.bits(99)));
        assertTrue(mapper.getEffectiveTopology().effectiveCpus().isEmpty());
    }
}
