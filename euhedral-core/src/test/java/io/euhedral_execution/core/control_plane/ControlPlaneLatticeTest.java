package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import euhedral.hardware_utils.ResourceMonitor;
import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.hardware_utils.SystemInfo.SocketInfo;
import euhedral.hardware_utils.TopologyMapper;
import euhedral.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import euhedral.hardware_utils.TopologyMapper.EffectiveSystemTopology;
import euhedral.hardware_utils.common.SystemUtilization.HardwareUtilization;
import euhedral.hardware_utils.common.SystemUtilization.SocketSnapshot;
import euhedral.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.core.config.LatticeConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class ControlPlaneLatticeTest {

    private static EffectiveSystemTopology getSystemTopology() {
        BitSet effectiveSockets = new BitSet(2);
        BitSet effectiveCores = new BitSet(4);
        BitSet effectiveCpus = new BitSet(8);

        effectiveSockets.set(0, 2);
        effectiveCores.set(0, 4);
        effectiveCpus.set(0, 8);

        List<EffectiveSocketTopology> topologies = new ArrayList<>(2);
        for (int i = 0; i < 2; i++) {
            BitSet cores = new BitSet(4);
            BitSet cpus = new BitSet(8);
            cores.set(i * 2, i * 2 + 1);
            cpus.set(i * 4, i * 4 + 4);
            topologies.add(new EffectiveSocketTopology(0, i, cores, cpus, null));
        }

        return new EffectiveSystemTopology(effectiveSockets,
                effectiveCores, effectiveCpus,
                topologies, 0);
    }
    private ControlPlaneShard mockShard;
    private MockedStatic<SystemInfo> mockSysInfo;
    private MockedConstruction<TopologyMapper> mockTopologyMapper;
    private MockedConstruction<ResourceMonitor> mockResourceMonitor;
    private HardwareUtilization mockUtilization;
    private EffectiveSystemTopology effectiveSystemTopology;
    private int version = 0;

    @BeforeEach
    public void setup() {
        mockSysInfo = Mockito.mockStatic(SystemInfo.class);
        BitSet cpus = new BitSet();
        cpus.set(0, 8);
        mockSysInfo.when(() -> SystemInfo.getCpuSet()).thenReturn(UnmodifiableBitSet.wrap(cpus));
        mockTopologyMapper = Mockito.mockConstructionWithAnswer(TopologyMapper.class,
                invocation -> {
                    Class<?> clazz = invocation.getMethod().getReturnType();
                    if (clazz.equals(int.class)) {
                        return version;
                    }
                    if (clazz.equals(void.class)) {
                        return null;
                    }
                    if (clazz.equals(EffectiveSystemTopology.class)) {
                        return effectiveSystemTopology;
                    }
                    return mock(EffectiveSystemTopology.class);
                });
        mockResourceMonitor = Mockito.mockConstructionWithAnswer(ResourceMonitor.class,
                invocation -> {
                    Class<?> clazz = invocation.getMethod().getReturnType();
                    if (clazz.equals(Void.TYPE)) {
                        return null;
                    }
                    if (clazz.equals(HardwareUtilization.class)) {
                        return mockUtilization;
                    }
                    return mock(ResourceMonitor.class);
                });
        ControlPlaneLattice plane = ControlPlaneLattice.getOrCreate();
        if (plane != null) {
            plane.close();
        }
        mockShard = mock(ControlPlaneShard.class);
        mockUtilization = mock(HardwareUtilization.class);
        effectiveSystemTopology = getSystemTopology();
    }

    @AfterEach
    public void tearDown() {
        mockSysInfo.close();
        mockTopologyMapper.close();
        mockResourceMonitor.close();
        version = 0;
    }

    @Test
    public void testInitialization() {
        SocketSnapshot[] snapshots = new SocketSnapshot[effectiveSystemTopology.effectiveSockets()
                .cardinality()];

        ControlPlaneLattice controlPlane = createControlPlaneWithMocks(snapshots);
        controlPlane.start();

        verify(mockShard, times(1)).clone(eq(0), any(), any());
        verify(mockShard, times(1)).clone(eq(1), any(), any());
        verify(mockShard, times(4)).isStarted();
        verify(mockShard, times(1)).start(eq(snapshots[0]),
                eq(effectiveSystemTopology.socketTopologies().get(0)),
                any());
        verify(mockShard, times(1)).start(eq(snapshots[1]),
                eq(effectiveSystemTopology.socketTopologies().get(1)),
                any());

        ResourceMonitor mockedRM = mockResourceMonitor.constructed().get(0);
        verify(mockedRM, times(1)).addListener(any());
        verify(mockedRM, times(1)).getUtilization();
        verify(mockUtilization, times(1)).getSocketSnapshot(eq(0), any(), anyDouble());
        verify(mockUtilization, times(1)).getSocketSnapshot(eq(1), any(), anyDouble());

        assertEquals(effectiveSystemTopology.globalVersion(), controlPlane.currentGlobalVersion);
        assertTrue(controlPlane.primed.get());
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilFalse(controlPlane.rebalancing);
        assertArrayEquals(new int[]{0, 1}, ControlPlaneLattice.getOrCreate().activeShardIds.get());
        assertEquals(2, controlPlane.shardHandles.length);
        assertEquals(2, controlPlane.shards.length);
        assertArrayEquals(new int[]{0, 0, 0, 0, 1, 1, 1, 1}, controlPlane.weightedShardMap.get());
    }

    @Test
    public void testGlobalRebalance() throws Exception {
        SocketSnapshot[] snapshots = new SocketSnapshot[effectiveSystemTopology.effectiveSockets()
                .cardinality()];

        ControlPlaneLattice controlPlane = createControlPlaneWithMocks(snapshots);
        controlPlane.start();

        effectiveSystemTopology.effectiveSockets().clear(0);
        effectiveSystemTopology.effectiveCores().clear(0, 2);
        effectiveSystemTopology.effectiveCpus().clear(0, 4);

        version = 3;
        effectiveSystemTopology = new EffectiveSystemTopology(
                effectiveSystemTopology.effectiveSockets(),
                effectiveSystemTopology.effectiveCores(),
                effectiveSystemTopology.effectiveCpus(), effectiveSystemTopology.socketTopologies(),
                version);

        when(mockShard.isStarted()).thenReturn(true);

        ControlPlaneLattice.getOrCreate().update(mockUtilization);

        Thread.sleep(100);

        verify(mockShard, times(1)).clone(eq(0), any(), any());
        verify(mockShard, times(1)).clone(eq(1), any(), any());
        verify(mockShard, times(1)).start(eq(snapshots[0]),
                eq(effectiveSystemTopology.socketTopologies().get(0)),
                any());
        verify(mockShard, times(1)).start(eq(snapshots[1]),
                eq(effectiveSystemTopology.socketTopologies().get(1)),
                any());

        verify(mockShard, times(0)).update(snapshots[0],
                effectiveSystemTopology.socketTopologies().get(0));
        verify(mockShard, times(0)).close();
        verify(mockShard, times(1)).shutDownShard(any());
        verify(mockShard, times(1)).update(snapshots[1],
                effectiveSystemTopology.socketTopologies().get(1));

        verify(mockUtilization, times(1)).getSocketSnapshot(eq(0), any(), anyDouble());
        verify(mockUtilization, times(2)).getSocketSnapshot(eq(1), any(), anyDouble());

        assertEquals(effectiveSystemTopology.globalVersion(), controlPlane.currentGlobalVersion);
        assertTrue(controlPlane.primed.get());
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilFalse(controlPlane.rebalancing);
        assertArrayEquals(new int[]{1}, controlPlane.activeShardIds.get());
        assertArrayEquals(new int[]{1, 1, 1, 1}, controlPlane.weightedShardMap.get());
    }

    private ControlPlaneLattice createControlPlaneWithMocks(SocketSnapshot[] snapshots) {
        for (int i = 0; i < snapshots.length; i++) {
            snapshots[i] = mock(SocketSnapshot.class);
            int id = i;
            SocketInfo socketInfo = mock(SocketInfo.class);
            mockSysInfo.when(() -> SystemInfo.getSocketInfo(id)).thenReturn(socketInfo);
            when(mockUtilization.getSocketSnapshot(eq(i), any(), anyDouble())).thenReturn(
                    snapshots[i]);
            when(mockShard.clone(eq(i), any(), any())).thenReturn(mockShard);
        }

        for (int i = 0; i < 8; i += 2) {
            CpuInfo fake1 = new CpuInfo(i, i * 2, i >> 2);
            CpuInfo fake2 = new CpuInfo(i + 1, i * 2, i >> 2);

            int id = i;
            mockSysInfo.when(() -> SystemInfo.getCpuInfo(id)).thenReturn(fake1);
            mockSysInfo.when(() -> SystemInfo.getCpuInfo(id + 1)).thenReturn(fake2);
        }
        mockSysInfo.when(SystemInfo::getMaxSocketId).thenReturn(1);

        when(mockShard.isStarted()).thenReturn(false);

        LatticeConfig config = new LatticeConfig("TestControlPlane", new BitSet(), Duration.ZERO, mockShard);
        return ControlPlaneLattice.getOrCreate(config);
    }

}