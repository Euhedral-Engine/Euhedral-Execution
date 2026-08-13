package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice.CacheReset;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.LatticeVertex;
import io.euhedral_execution.core.flow_control.RoutingPolicy;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.ingest.AbstractIngestSink;
import io.euhedral_execution.hardware_utils.ResourceMonitor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.TopologyMapper;
import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSystemTopology;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SocketSnapshot;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@Isolated
class ControlPlaneLatticeTest {

    private ControlPlaneShard baseShard;
    private ControlPlaneShard[] mockShards;
    private AtomicBoolean[] shardStarted;
    private MockedStatic<SystemInfo> mockSysInfo;
    private MockedConstruction<TopologyMapper> mockTopologyMapper;
    private MockedConstruction<ResourceMonitor> mockResourceMonitor;
    private HardwareUtilization mockUtilization;
    private EffectiveSystemTopology effectiveSystemTopology;
    private ControlPlaneLattice controlPlane;
    private AtomicBoolean deferShutdown;
    private AtomicReference<AtomicInteger> shutdownCounter;
    private int version = 0;

    @BeforeAll
    static void initializeSharedRoutingStateFromTheRealTopology() {
        new LatticeEdge(new AtomicBoolean());
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
            List<BitSet> coreToCpu = new ArrayList<>(4);
            for (int core = 0; core < 4; core++) {
                coreToCpu.add(new BitSet(8));
            }
            cores.set(i * 2, i * 2 + 2);
            cpus.set(i * 4, i * 4 + 4);
            coreToCpu.get(i * 2).set(i * 4, i * 4 + 2);
            coreToCpu.get(i * 2 + 1).set(i * 4 + 2, i * 4 + 4);
            topologies.add(new EffectiveSocketTopology(0, i, cores, cpus, coreToCpu));
        }

        return new EffectiveSystemTopology(effectiveSockets, effectiveCores, effectiveCpus, topologies, 0);
    }

    @BeforeEach
    void setup() {
        effectiveSystemTopology = getSystemTopology();
        mockUtilization = mock(HardwareUtilization.class);
        baseShard = mock(ControlPlaneShard.class);
        mockShards = new ControlPlaneShard[] {mock(ControlPlaneShard.class), mock(ControlPlaneShard.class)};
        shardStarted = new AtomicBoolean[] {new AtomicBoolean(), new AtomicBoolean()};
        deferShutdown = new AtomicBoolean();
        shutdownCounter = new AtomicReference<>();

        mockSysInfo = Mockito.mockStatic(SystemInfo.class);
        BitSet cpus = new BitSet();
        cpus.set(0, 8);
        mockSysInfo.when(SystemInfo::getCpuSet).thenReturn(UnmodifiableBitSet.wrap(cpus));
        mockSysInfo.when(SystemInfo::getMaxSocketId).thenReturn(1);
        for (int socket = 0; socket < 2; socket++) {
            int socketId = socket;
            mockSysInfo.when(() -> SystemInfo.getSocketInfo(socketId)).thenReturn(mock(SocketInfo.class));
        }
        for (int cpu = 0; cpu < 8; cpu++) {
            int cpuId = cpu;
            mockSysInfo.when(() -> SystemInfo.getCpuInfo(cpuId)).thenReturn(new CpuInfo(cpuId, cpuId / 2, cpuId / 4));
        }

        mockTopologyMapper = Mockito.mockConstruction(TopologyMapper.class, (mock, context) -> {
            when(mock.getGlobalVersion()).thenAnswer(invocation -> version);
            when(mock.getEffectiveTopology()).thenAnswer(invocation -> effectiveSystemTopology);
        });
        mockResourceMonitor = Mockito.mockConstruction(
                ResourceMonitor.class,
                (mock, context) -> when(mock.getUtilization()).thenReturn(mockUtilization));

        for (int socket = 0; socket < mockShards.length; socket++) {
            int socketId = socket;
            when(baseShard.clone(eq(socketId), any(), any())).thenReturn(mockShards[socketId]);
            when(mockShards[socketId].isStarted()).thenAnswer(invocation -> shardStarted[socketId].get());
            doAnswer(invocation -> {
                        shardStarted[socketId].set(true);
                        return null;
                    })
                    .when(mockShards[socketId])
                    .start(any(), any(), any());
        }

        doAnswer(invocation -> {
                    shardStarted[0].set(false);
                    AtomicInteger counter = invocation.getArgument(0);
                    shutdownCounter.set(counter);
                    if (!deferShutdown.get()) {
                        counter.decrementAndGet();
                    }
                    return null;
                })
                .when(mockShards[0])
                .shutDownShard(any());
    }

    @AfterEach
    void tearDown() {
        if (controlPlane != null) {
            controlPlane.close();
        }
        if (mockResourceMonitor != null) {
            mockResourceMonitor.close();
        }
        if (mockTopologyMapper != null) {
            mockTopologyMapper.close();
        }
        if (mockSysInfo != null) {
            mockSysInfo.close();
        }
        version = 0;
    }

    @Test
    void testInitialization() {
        SocketSnapshot[] snapshots =
                new SocketSnapshot[effectiveSystemTopology.effectiveSockets().cardinality()];

        controlPlane = createControlPlaneWithMocks(snapshots);
        controlPlane.start();

        verify(baseShard).clone(eq(0), any(), any());
        verify(baseShard).clone(eq(1), any(), any());
        verify(mockShards[0])
                .start(
                        eq(snapshots[0]),
                        eq(effectiveSystemTopology.socketTopologies().get(0)),
                        any());
        verify(mockShards[1])
                .start(
                        eq(snapshots[1]),
                        eq(effectiveSystemTopology.socketTopologies().get(1)),
                        any());

        ResourceMonitor mockedRM = mockResourceMonitor.constructed().get(0);
        verify(mockedRM).addListener(any());
        verify(mockedRM).getUtilization();

        assertEquals(effectiveSystemTopology.globalVersion(), controlPlane.currentGlobalVersion);
        assertTrue(controlPlane.primed.get());
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilFalse(controlPlane.rebalancing);
        assertArrayEquals(new int[] {0, 1}, controlPlane.activeShardIds.get());
        assertEquals(2, controlPlane.shardHandles.length);
        assertEquals(2, controlPlane.shards.length);
        assertArrayEquals(new int[] {0, 0, 0, 0, 1, 1, 1, 1}, controlPlane.weightedShardMap.get());
    }

    @Test
    void testGlobalRebalance() {
        SocketSnapshot[] snapshots =
                new SocketSnapshot[effectiveSystemTopology.effectiveSockets().cardinality()];

        controlPlane = createControlPlaneWithMocks(snapshots);
        controlPlane.start();

        BitSet retainedSockets =
                (BitSet) effectiveSystemTopology.effectiveSockets().clone();
        BitSet retainedCores = (BitSet) effectiveSystemTopology.effectiveCores().clone();
        BitSet retainedCpus = (BitSet) effectiveSystemTopology.effectiveCpus().clone();
        retainedSockets.clear(0);
        retainedCores.clear(0, 2);
        retainedCpus.clear(0, 4);

        version = 3;
        effectiveSystemTopology = new EffectiveSystemTopology(
                retainedSockets, retainedCores, retainedCpus, effectiveSystemTopology.socketTopologies(), version);
        deferShutdown.set(true);

        controlPlane.update(mockUtilization);
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> shutdownCounter.get() != null);
        try {
            assertTrue(controlPlane.rebalancing.get());
            assertTrue(controlPlane.ingestController.get().getDrainFlag().get());
        } finally {
            shutdownCounter.get().decrementAndGet();
        }
        Awaitility.await().atMost(Duration.ofSeconds(2)).untilFalse(controlPlane.rebalancing);
        assertFalse(controlPlane.ingestController.get().getDrainFlag().get());

        verify(baseShard).clone(eq(0), any(), any());
        verify(baseShard).clone(eq(1), any(), any());
        verify(mockShards[0])
                .start(
                        eq(snapshots[0]),
                        eq(effectiveSystemTopology.socketTopologies().get(0)),
                        any());
        verify(mockShards[1])
                .start(
                        eq(snapshots[1]),
                        eq(effectiveSystemTopology.socketTopologies().get(1)),
                        any());

        verify(mockShards[0], never()).update(any(), any());
        verify(mockShards[0]).shutDownShard(any());
        verify(mockShards[1])
                .update(snapshots[1], effectiveSystemTopology.socketTopologies().get(1));

        assertEquals(effectiveSystemTopology.globalVersion(), controlPlane.currentGlobalVersion);
        assertTrue(controlPlane.primed.get());
        assertArrayEquals(new int[] {1}, controlPlane.activeShardIds.get());
        assertArrayEquals(new int[] {1, 1, 1, 1}, controlPlane.weightedShardMap.get());
    }

    private ControlPlaneLattice createControlPlaneWithMocks(SocketSnapshot[] snapshots) {
        for (int i = 0; i < snapshots.length; i++) {
            snapshots[i] = mock(SocketSnapshot.class);
            int id = i;
            SocketInfo socketInfo = mock(SocketInfo.class);
            mockSysInfo.when(() -> SystemInfo.getSocketInfo(id)).thenReturn(socketInfo);
            when(mockUtilization.getSocketSnapshot(eq(i), any(), anyDouble())).thenReturn(snapshots[i]);
        }

        LatticeConfig config = new LatticeConfig("TestControlPlane", new BitSet(), Duration.ZERO, baseShard);
        return ControlPlaneLattice.getOrCreate(config);
    }

    @Test
    void resourceMonitorUpdatesPropagateToControlPlaneFragment() {
        LatticeConfig config = LatticeConfig.ofDefaults();
        ControlPlaneLattice lattice = ControlPlaneLattice.getOrCreate(config);
        try {
            Assertions.assertNotNull(lattice.resourceMonitor);
        } finally {
            lattice.close();
        }
    }

    @Test
    void testFactoryMethodsAndSingletonSemantics() {
        controlPlane = ControlPlaneLattice.getOrCreate();
        assertNotNull(controlPlane);
        assertEquals("EuhedralLattice", controlPlane.name);

        ControlPlaneLattice same = ControlPlaneLattice.getOrCreate("OtherName", "OtherShard");
        assertSame(controlPlane, same);

        assertThrows(NullPointerException.class, () -> ControlPlaneLattice.getOrCreate((LatticeConfig) null));

        controlPlane.close();

        controlPlane = ControlPlaneLattice.getOrCreate("CustomLattice", "CustomShard");
        assertNotNull(controlPlane);
        assertEquals("CustomLattice", controlPlane.name);

        controlPlane.close();

        LatticeConfig blankNameConfig = new LatticeConfig("   ", new BitSet(), Duration.ZERO, baseShard);
        controlPlane = ControlPlaneLattice.getOrCreate(blankNameConfig);
        assertEquals("ControlPlaneLattice", controlPlane.name);
    }

    @Test
    void testIdempotentStartAndClose() {
        SocketSnapshot[] snapshots =
                new SocketSnapshot[effectiveSystemTopology.effectiveSockets().cardinality()];
        controlPlane = createControlPlaneWithMocks(snapshots);

        controlPlane.start();
        assertTrue(controlPlane.started.get());
        assertTrue(controlPlane.ready.get());

        controlPlane.start();
        verify(baseShard, times(1)).clone(eq(0), any(), any());
        verify(baseShard, times(1)).clone(eq(1), any(), any());

        controlPlane.close();
        assertTrue(controlPlane.closed.get());

        controlPlane.close();
    }

    @Test
    void testAddUpstreamValidationAndBehavior() {
        SocketSnapshot[] snapshots =
                new SocketSnapshot[effectiveSystemTopology.effectiveSockets().cardinality()];
        controlPlane = createControlPlaneWithMocks(snapshots);

        assertThrows(NullPointerException.class, () -> controlPlane.addUpstream((LatticeSource) null));
        assertThrows(NullPointerException.class, () -> controlPlane.addUpstream((AbstractIngestSink) null));

        AbstractIngestSink mockSink = mock(AbstractIngestSink.class);
        LatticeSource mockSource = mock(LatticeSource.class);
        when(mockSink.getDelegate()).thenReturn(mockSource);

        assertFalse(controlPlane.started.get());
        controlPlane.addUpstream(mockSink);
        assertTrue(controlPlane.started.get());
        verify(mockSink).getDelegate();

        controlPlane.close();
        LatticeSource sourceAfterClose = mock(LatticeSource.class);
        RuntimeException ex = assertThrows(RuntimeException.class, () -> controlPlane.addUpstream(sourceAfterClose));
        assertTrue(ex.getMessage().contains("permanently closed"));
    }

    @Test
    void testRoutingPolicyAndFallback() {
        SocketSnapshot[] snapshots =
                new SocketSnapshot[effectiveSystemTopology.effectiveSockets().cardinality()];
        controlPlane = createControlPlaneWithMocks(snapshots);
        controlPlane.start();

        LatticeVertex controller = controlPlane.ingestController.get();

        LatticeEdge edge0 = mock(LatticeEdge.class);
        LatticeEdge edge1 = mock(LatticeEdge.class);
        controlPlane.shardHandles[0] = edge0;
        controlPlane.shardHandles[1] = edge1;

        controller.setDrain(true);
        controller.setDownstreamMapping(effectiveSystemTopology.effectiveSockets(), controlPlane.shardHandles);
        controller.setDrain(false);

        // SOCKET_LOCAL policy with origin socket 1 -> routes to socket 1 (edge1)
        TestFrame socket1Frame = new TestFrame(100L);
        socket1Frame.setRoutingPolicy(RoutingPolicy.SOCKET_LOCAL);
        socket1Frame.setOrigin(new CpuInfo(4, 2, 1)); // CPU 4 -> socket 1
        controller.push(socket1Frame);
        verify(edge1).push(socket1Frame);

        // SOCKET_LOCAL policy with null origin -> falls back to default hash routing
        TestFrame nullOriginFrame = new TestFrame(12345L);
        nullOriginFrame.setRoutingPolicy(RoutingPolicy.SOCKET_LOCAL);
        nullOriginFrame.setOrigin(null);
        controller.push(nullOriginFrame);

        // SOCKET_LOCAL policy with origin socket 0, but handle at socket 0 is null -> falls back to default hash
        // routing
        controlPlane.shardHandles[0] = null;
        TestFrame missingHandleFrame = new TestFrame(999L);
        missingHandleFrame.setRoutingPolicy(RoutingPolicy.SOCKET_LOCAL);
        missingHandleFrame.setOrigin(new CpuInfo(0, 0, 0)); // Socket 0
        controller.push(missingHandleFrame);

        // ANYWHERE policy -> uses default hash routing
        TestFrame anywhereFrame = new TestFrame(555L);
        anywhereFrame.setRoutingPolicy(RoutingPolicy.ANYWHERE);
        controller.push(anywhereFrame);
    }

    @Test
    void testClearValidationAndExecution() {
        SocketSnapshot[] snapshots =
                new SocketSnapshot[effectiveSystemTopology.effectiveSockets().cardinality()];
        controlPlane = createControlPlaneWithMocks(snapshots);

        // Null and non-positive timeout checks
        assertThrows(NullPointerException.class, () -> controlPlane.clear(null));
        assertThrows(IllegalArgumentException.class, () -> controlPlane.clear(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> controlPlane.clear(Duration.ofMillis(-10)));

        // Unstarted lattice returns CacheReset(0, 0)
        CacheReset unstartedReset = controlPlane.clear(Duration.ofSeconds(1));
        assertEquals(0, unstartedReset.clearedFrames());
        assertEquals(0, unstartedReset.activeWorkers());

        // Start lattice
        controlPlane.start();

        // Closed lattice throws IllegalStateException
        controlPlane.closed.set(true);
        assertThrows(IllegalStateException.class, () -> controlPlane.clear(Duration.ofSeconds(1)));
        controlPlane.closed.set(false);

        // Reset already in progress throws IllegalStateException
        controlPlane.resetting.set(true);
        assertThrows(IllegalStateException.class, () -> controlPlane.clear(Duration.ofSeconds(1)));
        controlPlane.resetting.set(false);

        // Rebalance timeout throws IllegalStateException
        controlPlane.rebalancing.set(true);
        assertThrows(IllegalStateException.class, () -> controlPlane.clear(Duration.ofMillis(10)));
        controlPlane.rebalancing.set(false);

        // Successful reset
        when(mockShards[0].resetForNextTrial(anyLong())).thenReturn(50L);
        when(mockShards[1].resetForNextTrial(anyLong())).thenReturn(30L);
        when(mockShards[0].getActiveCores()).thenReturn(4);
        when(mockShards[1].getActiveCores()).thenReturn(2);

        CacheReset reset = controlPlane.clear(Duration.ofSeconds(1));
        assertEquals(80L, reset.clearedFrames());
        assertEquals(6, reset.activeWorkers());

        verify(mockShards[0]).resetForNextTrial(anyLong());
        verify(mockShards[1]).resetForNextTrial(anyLong());
    }

    @Test
    void testGetActiveWorkers() {
        SocketSnapshot[] snapshots =
                new SocketSnapshot[effectiveSystemTopology.effectiveSockets().cardinality()];
        controlPlane = createControlPlaneWithMocks(snapshots);
        controlPlane.start();

        when(mockShards[0].getActiveCores()).thenReturn(4);
        when(mockShards[1].getActiveCores()).thenReturn(2);

        assertEquals(6, controlPlane.getActiveWorkers());

        // Test with a null entry in shards array
        controlPlane.shards[1] = null;
        assertEquals(4, controlPlane.getActiveWorkers());
    }

    @Test
    void testIsDrained() {
        SocketSnapshot[] snapshots =
                new SocketSnapshot[effectiveSystemTopology.effectiveSockets().cardinality()];
        controlPlane = createControlPlaneWithMocks(snapshots);
        controlPlane.start();

        LatticeVertex mockController = mock(LatticeVertex.class);
        controlPlane.ingestController.set(mockController);

        // Ingest controller is not drained
        when(mockController.isDrained()).thenReturn(false);
        assertFalse(controlPlane.isDrained());

        // Ingest controller is drained, but shard 0 is not drained
        when(mockController.isDrained()).thenReturn(true);
        when(mockShards[0].isDrained()).thenReturn(false);
        when(mockShards[1].isDrained()).thenReturn(true);
        assertFalse(controlPlane.isDrained());

        // Both ingest controller and all shards are drained
        when(mockShards[0].isDrained()).thenReturn(true);
        assertTrue(controlPlane.isDrained());

        // Null controller or null shard handled safely
        controlPlane.ingestController.set(null);
        controlPlane.shards[0] = null;
        assertTrue(controlPlane.isDrained());
    }

    @Test
    void testUpdateWhenResettingOrZeroCpus() {
        SocketSnapshot[] snapshots =
                new SocketSnapshot[effectiveSystemTopology.effectiveSockets().cardinality()];
        controlPlane = createControlPlaneWithMocks(snapshots);
        controlPlane.start();

        // When resetting is true, update returns early
        controlPlane.resetting.set(true);
        controlPlane.update(mockUtilization);
        verify(mockShards[0], never()).update(any(), any());
        controlPlane.resetting.set(false);

        // Topology change with 0 effective CPUs
        version = 10;
        BitSet emptySockets = new BitSet();
        BitSet emptyCores = new BitSet();
        BitSet emptyCpus = new BitSet();
        effectiveSystemTopology = new EffectiveSystemTopology(emptySockets, emptyCores, emptyCpus, List.of(), version);

        controlPlane.update(mockUtilization);
        assertEquals(10, controlPlane.currentGlobalVersion);
        assertEquals(0, controlPlane.activeShardIds.get().length);
    }

    @Test
    void testUpdateProportionalQuotaAllocation() {
        SocketSnapshot[] snapshots =
                new SocketSnapshot[effectiveSystemTopology.effectiveSockets().cardinality()];
        controlPlane = createControlPlaneWithMocks(snapshots);
        controlPlane.start();

        when(mockUtilization.quotaCpus()).thenReturn(4.0);

        // Call update with same version
        controlPlane.update(mockUtilization);

        // Total effective CPUs = 8. Socket 0 has 4 cpus, Socket 1 has 4 cpus.
        // Proportional quota = (4/8) * 4.0 = 2.0 for each socket
        verify(mockUtilization).getSocketSnapshot(eq(0), any(), eq(2.0));
        verify(mockUtilization).getSocketSnapshot(eq(1), any(), eq(2.0));
    }

    static class TestFrame extends AbstractFrame {
        TestFrame(long idHash) {
            super(idHash);
        }
    }
}
