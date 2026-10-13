package io.euhedral_execution.hardware_utils.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.topology.TopologyMapper.EffectiveSocketTopology;
import io.euhedral_execution.hardware_utils.topology.TopologyMapper.EffectiveSystemTopology;
import io.euhedral_execution.hardware_utils.topology.internal.TopologyModel;
import io.euhedral_execution.hardware_utils.topology.internal.TopologyQueries;
import java.util.List;
import org.junit.jupiter.api.Test;

class TopologyQueriesTest {

    private final TopologyModel model = TopologyHelpers.sparseThreeSocketModel();

    @Test
    void presenceQueriesNeverThrowAndAgreeWithLookups() {
        assertTrue(TopologyQueries.isActiveCpu(model, 0));
        assertFalse(TopologyQueries.isActiveCpu(model, 2));
        assertTrue(TopologyQueries.isActiveCpu(model, 3));
        assertFalse(TopologyQueries.isActiveCpu(model, -1));
        assertFalse(TopologyQueries.isActiveCpu(model, Integer.MAX_VALUE));
        assertFalse(TopologyQueries.isActiveCpu(model, Integer.MIN_VALUE));

        for (int id = -2; id < 12; id++) {
            assertEquals(model.coreInfo().get(id) != null, TopologyQueries.isActiveCore(model, id), "core " + id);
            assertEquals(model.socketInfo().get(id) != null, TopologyQueries.isActiveSocket(model, id), "socket " + id);
        }
        assertFalse(TopologyQueries.isActiveCore(model, Integer.MAX_VALUE));
        assertFalse(TopologyQueries.isActiveSocket(model, -1));
        assertTrue(TopologyQueries.isActiveSocket(model, 2));
    }

    @Test
    void requireCpuInfoReturnsLookupOrNamesIdAndActiveSet() {
        assertEquals(model.cpuInfo().get(3), TopologyQueries.requireCpuInfo(model, 3));
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> TopologyQueries.requireCpuInfo(model, 2));
        assertEquals("cpu 2 is not an active logical CPU (active: 0-1,3,6-7)", e.getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TopologyQueries.requireCpuInfo(model, -1))
                .getMessage()
                .contains("cpu -1"));
    }

    @Test
    void requireCoreSocketAndCacheLayout() {
        assertEquals(model.coreInfo().get(0), TopologyQueries.requireCoreInfo(model, 0));
        assertEquals(model.socketInfo().get(1), TopologyQueries.requireSocketInfo(model, 1));
        assertEquals(model.cacheLayout().get(7), TopologyQueries.requireCacheLayout(model, 7));

        assertTrue(assertThrows(IllegalArgumentException.class, () -> TopologyQueries.requireCoreInfo(model, 99))
                .getMessage()
                .contains("core 99"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TopologyQueries.requireSocketInfo(model, 3))
                .getMessage()
                .contains("socket 3"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TopologyQueries.requireCacheLayout(model, 2))
                .getMessage()
                .contains("cpu 2"));
    }

    @Test
    void activeSocketsHasNoHolesWhileIndexedListKeepsThem() {
        TopologyMapper mapper = new TopologyMapper(model, TopologyHelpers.bits(0, 1, 6, 7));
        mapper.update(TopologyHelpers.utilization(TopologyHelpers.bits(0, 1, 6, 7)));
        EffectiveSystemTopology topology = mapper.getEffectiveTopology();

        assertEquals(3, topology.socketTopologies().size());
        assertNull(topology.socketTopologies().get(1));

        List<EffectiveSocketTopology> active = topology.activeSockets();
        assertEquals(2, active.size());
        for (EffectiveSocketTopology socket : active) {
            assertNotNull(socket);
        }
        assertEquals(0, active.get(0).socketId());
        assertEquals(2, active.get(1).socketId());
        assertSame(topology.socketTopologies().get(2), active.get(1));
        assertThrows(UnsupportedOperationException.class, () -> active.add(active.get(0)));
    }

    @Test
    void activeSocketsIsEmptyBeforeFirstPublication() {
        TopologyMapper mapper = new TopologyMapper(model, TopologyHelpers.bits(0));
        assertTrue(mapper.getEffectiveTopology().activeSockets().isEmpty());
    }
}
