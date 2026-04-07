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

import euhedral.io.utils.NumaMapper;
import euhedral.io.utils.NumaMapper.NodeTopology;
import euhedral.io.utils.NumaMapper.SystemTopology;
import euhedral.io.utils.ResourceMonitor;
import euhedral.io.utils.SystemUtilization.HardwareUtilization;
import euhedral.io.utils.SystemUtilization.NodeSnapshot;
import java.time.Duration;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ControlPlaneTest {

    private ControlPlaneShard mockShard;
    private NumaMapper mockMapper;
    private ResourceMonitor mockResourceMonitor;
    private HardwareUtilization mockUtilization;

    @BeforeEach
    public void setup() {
        ControlPlane plane = ControlPlane.get();
        if (plane != null) {
            plane.close();
        }
        mockShard = mock(ControlPlaneShard.class);
        mockMapper = mock(NumaMapper.class);
        mockResourceMonitor = mock(ResourceMonitor.class);
        mockUtilization = mock(HardwareUtilization.class);
    }

    @Test
    public void testInitialization() {
        SystemTopology systemTopology = getSystemTopology();

        NodeSnapshot[] snapshots = new NodeSnapshot[systemTopology.effectiveNodes().get()
                .cardinality()];

        createControlPlaneWithMocks(systemTopology, snapshots);

        verify(mockMapper, times(1)).getSystemTopology();
        verify(mockMapper, times(2)).getNodeCount();

        verify(mockShard, times(1)).clone(eq(0), any());
        verify(mockShard, times(1)).clone(eq(1), any());
        verify(mockShard, times(4)).isStarted();
        verify(mockShard, times(1)).start(eq(snapshots[0]),
                eq(systemTopology.nodeTopologies().get(0)),
                any());
        verify(mockShard, times(1)).start(eq(snapshots[1]),
                eq(systemTopology.nodeTopologies().get(1)),
                any());

        verify(mockResourceMonitor, times(1)).addListener();
        verify(mockResourceMonitor, times(1)).getUtilization();
        verify(mockUtilization, times(1)).getNodeSnapshot(eq(0), any(), anyDouble());
        verify(mockUtilization, times(1)).getNodeSnapshot(eq(1), any(), anyDouble());

        ControlPlane controlPlane = ControlPlane.get();
        assertEquals(systemTopology.globalVersion().get(), controlPlane.currentGlobalVersion);
        assertTrue(controlPlane.primed);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilFalse(controlPlane.rebalancing);
        assertArrayEquals(new int[]{0, 1}, ControlPlane.get().activeNodeIds);
        assertEquals(2, controlPlane.shardHandles.length);
        assertEquals(2, controlPlane.shards.length);
        assertArrayEquals(new int[]{0, 0, 0, 0, 1, 1, 1, 1}, controlPlane.weightedShardMap);
    }

    @Test
    public void testGlobalRebalance() throws Exception {
        SystemTopology systemTopology = getSystemTopology();

        NodeSnapshot[] snapshots = new NodeSnapshot[systemTopology.effectiveNodes().get()
                .cardinality()];

        createControlPlaneWithMocks(systemTopology, snapshots);

        systemTopology.effectiveNodes().get().clear(0);
        systemTopology.effectiveCores().get().clear(0, 2);
        systemTopology.effectiveCpus().get().clear(0, 4);
        systemTopology.globalVersion().incrementAndGet();

        when(mockShard.isStarted()).thenReturn(true);

        ControlPlane.get().update(mockUtilization);

        Thread.sleep(100);

        verify(mockShard, times(1)).clone(eq(0), any());
        verify(mockShard, times(1)).clone(eq(1), any());
        verify(mockShard, times(1)).start(eq(snapshots[0]),
                eq(systemTopology.nodeTopologies().get(0)),
                any());
        verify(mockShard, times(1)).start(eq(snapshots[1]),
                eq(systemTopology.nodeTopologies().get(1)),
                any());

        verify(mockShard, times(0)).update(snapshots[0], systemTopology.nodeTopologies().get(0));
        verify(mockShard, times(0)).close();
        verify(mockShard, times(1)).shutDownShard(any());
        verify(mockShard, times(1)).update(snapshots[1], systemTopology.nodeTopologies().get(1));

        verify(mockUtilization, times(1)).getNodeSnapshot(eq(0), any(), anyDouble());
        verify(mockUtilization, times(2)).getNodeSnapshot(eq(1), any(), anyDouble());

        ControlPlane controlPlane = ControlPlane.get();
        assertEquals(systemTopology.globalVersion().get(), controlPlane.currentGlobalVersion);
        assertTrue(controlPlane.primed);
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilFalse(controlPlane.rebalancing);
        assertArrayEquals(new int[]{1}, controlPlane.activeNodeIds);
        assertArrayEquals(new int[]{1, 1, 1, 1}, controlPlane.weightedShardMap);
    }

    private static SystemTopology getSystemTopology() {
        BitSet effectiveNodes = new BitSet(2);
        BitSet effectiveCores = new BitSet(4);
        BitSet effectiveCpus = new BitSet(8);

        effectiveNodes.set(0, 2);
        effectiveCores.set(0, 4);
        effectiveCpus.set(0, 8);

        AtomicReferenceArray<NodeTopology> topologies = new AtomicReferenceArray<>(2);
        for (int i = 0; i < topologies.length(); i++) {
            BitSet cores = new BitSet(4);
            BitSet cpus = new BitSet(8);
            cores.set(i * 2, i * 2 + 1);
            cpus.set(i * 4, i * 4 + 4);
            topologies.set(i, new NodeTopology(new AtomicInteger(0), new AtomicReference<>(cores),
                    new AtomicReference<>(cpus), new AtomicReference<>()));
        }

        return new SystemTopology(new AtomicReference<>(effectiveNodes),
                new AtomicReference<>(effectiveCores), new AtomicReference<>(effectiveCpus),
                topologies, new AtomicInteger(0));
    }

    private void createControlPlaneWithMocks(SystemTopology systemTopology,
            NodeSnapshot[] snapshots) {
        for (int i = 0; i < snapshots.length; i++) {
            snapshots[i] = mock(NodeSnapshot.class);
            when(mockUtilization.getNodeSnapshot(eq(i), any(), anyDouble())).thenReturn(
                    snapshots[i]);
            when(mockShard.clone(eq(i), any())).thenReturn(mockShard);
        }
        when(mockMapper.getNodeId(0)).thenReturn(0);
        when(mockMapper.getNodeId(1)).thenReturn(0);
        when(mockMapper.getNodeId(2)).thenReturn(1);
        when(mockMapper.getNodeId(3)).thenReturn(1);

        for(int i = 0; i < 4; i++) {
            when(mockMapper.getPhysicalCore(i * 2)).thenReturn(i);
            when(mockMapper.getPhysicalCore(i * 2 + 1)).thenReturn(i);
        }

        when(mockMapper.getSystemTopology()).thenReturn(systemTopology);
        when(mockMapper.getNodeCount()).thenReturn(systemTopology.nodeTopologies().length());
        when(mockShard.isStarted()).thenReturn(false);

        ControlPlane.getOrCreate("TestControlPlane", mockShard);
    }
}