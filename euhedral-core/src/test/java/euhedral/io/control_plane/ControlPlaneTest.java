package euhedral.io.control_plane;

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

import euhedral.hardware_utils.EffectiveTopology;
import euhedral.hardware_utils.EffectiveTopology.EffectiveSocketTopology;
import euhedral.hardware_utils.EffectiveTopology.EffectiveSystemTopology;
import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.hardware_utils.common.SystemUtilization.HardwareUtilization;
import euhedral.hardware_utils.common.SystemUtilization.SocketSnapshot;
import euhedral.io.utils.FluxResourceMonitor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

class ControlPlaneTest {

    private ControlPlaneShard mockShard;
    private final static MockedStatic<EffectiveTopology> mockMapper = Mockito.mockStatic(EffectiveTopology.class);;
    private final static MockedStatic<SystemInfo> mockSysInfo = Mockito.mockStatic(SystemInfo.class);;
    private static MockedConstruction<FluxResourceMonitor> mockResourceMonitor;
    private static HardwareUtilization mockUtilization;

    @BeforeAll
    static void init() {
        mockResourceMonitor = Mockito.mockConstructionWithAnswer(FluxResourceMonitor.class, invocation -> {
            Class<?> clazz = invocation.getMethod().getReturnType();
            if(clazz.equals(Void.TYPE)) {
                return null;
            }
            if(clazz.equals(HardwareUtilization.class)) {
                return mockUtilization;
            }
            if(clazz.equals(Flux.class)) {
                return Flux.empty();
            }
            return mock(FluxResourceMonitor.class);
        });
    }

    @BeforeEach
    public void setup() {
        ControlPlane plane = ControlPlane.get();
        if (plane != null) {
            plane.close();
        }
        mockShard = mock(ControlPlaneShard.class);
        mockUtilization = mock(HardwareUtilization.class);
    }

    @Test
    public void testInitialization() throws Exception {
        mockMapper.reset();
        mockSysInfo.reset();
        EffectiveSystemTopology effectiveTopology = getSystemTopology();

        SocketSnapshot[] snapshots = new SocketSnapshot[effectiveTopology.effectiveSockets()
                .cardinality()];

        createControlPlaneWithMocks(effectiveTopology, snapshots);

        mockMapper.verify(EffectiveTopology::getEffectiveTopology, times(2));

        verify(mockShard, times(1)).clone(eq(0), any());
        verify(mockShard, times(1)).clone(eq(1), any());
        verify(mockShard, times(4)).isStarted();
        verify(mockShard, times(1)).start(eq(snapshots[0]),
                eq(effectiveTopology.socketTopologies().get(0)),
                any());
        verify(mockShard, times(1)).start(eq(snapshots[1]),
                eq(effectiveTopology.socketTopologies().get(1)),
                any());

        FluxResourceMonitor mockedRM = mockResourceMonitor.constructed().get(0);
        verify(mockedRM, times(1)).addListener();
        verify(mockedRM, times(2)).getUtilization();
        verify(mockUtilization, times(1)).getSocketSnapshot(eq(0), any(), anyDouble());
        verify(mockUtilization, times(1)).getSocketSnapshot(eq(1), any(), anyDouble());

        ControlPlane controlPlane = ControlPlane.get();
        assertEquals(effectiveTopology.globalVersion(), controlPlane.currentGlobalVersion);
        assertTrue(controlPlane.primed.get());
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilFalse(controlPlane.rebalancing);
        assertArrayEquals(new int[]{0, 1}, ControlPlane.get().activeShardIds.get());
        assertEquals(2, controlPlane.shardHandles.length);
        assertEquals(2, controlPlane.shards.length);
        assertArrayEquals(new int[]{0, 0, 0, 0, 1, 1, 1, 1}, controlPlane.weightedShardMap.get());
    }

    @Test
    public void testGlobalRebalance() throws Exception {
        mockMapper.reset();
        mockSysInfo.reset();
        EffectiveSystemTopology effectiveTopology = getSystemTopology();

        SocketSnapshot[] snapshots = new SocketSnapshot[effectiveTopology.effectiveSockets()
                .cardinality()];

        createControlPlaneWithMocks(effectiveTopology, snapshots);

        effectiveTopology.effectiveSockets().clear(0);
        effectiveTopology.effectiveCores().clear(0, 2);
        effectiveTopology.effectiveCpus().clear(0, 4);

        EffectiveSystemTopology updatedTopology = new EffectiveSystemTopology(
                effectiveTopology.effectiveSockets(), effectiveTopology.effectiveCores(),
                effectiveTopology.effectiveCpus(), effectiveTopology.socketTopologies(), 3);

        mockMapper.when(EffectiveTopology::getEffectiveTopology).thenReturn(updatedTopology);
        mockMapper.when(EffectiveTopology::getGlobalVersion).thenReturn(3);

        when(mockShard.isStarted()).thenReturn(true);

        ControlPlane.get().update(mockUtilization);

        Thread.sleep(100);

        verify(mockShard, times(1)).clone(eq(0), any());
        verify(mockShard, times(1)).clone(eq(1), any());
        verify(mockShard, times(1)).start(eq(snapshots[0]),
                eq(updatedTopology.socketTopologies().get(0)),
                any());
        verify(mockShard, times(1)).start(eq(snapshots[1]),
                eq(updatedTopology.socketTopologies().get(1)),
                any());

        verify(mockShard, times(0)).update(snapshots[0],
                updatedTopology.socketTopologies().get(0));
        verify(mockShard, times(0)).close();
        verify(mockShard, times(1)).shutDownShard(any());
        verify(mockShard, times(1)).update(snapshots[1],
                updatedTopology.socketTopologies().get(1));

        verify(mockUtilization, times(1)).getSocketSnapshot(eq(0), any(), anyDouble());
        verify(mockUtilization, times(2)).getSocketSnapshot(eq(1), any(), anyDouble());

        ControlPlane controlPlane = ControlPlane.get();
        assertEquals(updatedTopology.globalVersion(), controlPlane.currentGlobalVersion);
        assertTrue(controlPlane.primed.get());
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilFalse(controlPlane.rebalancing);
        assertArrayEquals(new int[]{1}, controlPlane.activeShardIds.get());
        assertArrayEquals(new int[]{1, 1, 1, 1}, controlPlane.weightedShardMap.get());
    }

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

    private void createControlPlaneWithMocks(EffectiveSystemTopology effectiveTopology,
            SocketSnapshot[] snapshots) {
        for (int i = 0; i < snapshots.length; i++) {
            snapshots[i] = mock(SocketSnapshot.class);
            when(mockUtilization.getSocketSnapshot(eq(i), any(), anyDouble())).thenReturn(
                    snapshots[i]);
            when(mockShard.clone(eq(i), any())).thenReturn(mockShard);
        }

        for(int i = 0; i < 8; i += 2) {
            CpuInfo fake1 = new CpuInfo(i, i * 2, i >> 2);
            CpuInfo fake2 = new CpuInfo(i + 1, i * 2, i >> 2);

            int id = i;
            mockSysInfo.when(() -> SystemInfo.getCpuInfo(id)).thenReturn(fake1);
            mockSysInfo.when(() -> SystemInfo.getCpuInfo(id + 1)).thenReturn(fake2);
        }
        mockSysInfo.when(SystemInfo::getMaxSocketId).thenReturn(1);

        mockMapper.when(EffectiveTopology::getEffectiveTopology).thenReturn(effectiveTopology);
        when(mockShard.isStarted()).thenReturn(false);

        ControlPlane.getOrCreate("TestControlPlane", mockShard);
    }

}