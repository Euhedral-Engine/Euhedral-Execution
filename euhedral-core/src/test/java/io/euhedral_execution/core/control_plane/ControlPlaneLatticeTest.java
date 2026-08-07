package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.flow_control.LatticeEdge;
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
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@Isolated
class ControlPlaneLatticeTest {

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

    return new EffectiveSystemTopology(
        effectiveSockets, effectiveCores, effectiveCpus, topologies, 0);
  }

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

  @BeforeEach
  void setup() {
    effectiveSystemTopology = getSystemTopology();
    mockUtilization = mock(HardwareUtilization.class);
    baseShard = mock(ControlPlaneShard.class);
    mockShards =
        new ControlPlaneShard[] {mock(ControlPlaneShard.class), mock(ControlPlaneShard.class)};
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
      mockSysInfo
          .when(() -> SystemInfo.getCpuInfo(cpuId))
          .thenReturn(new CpuInfo(cpuId, cpuId / 2, cpuId / 4));
    }

    mockTopologyMapper =
        Mockito.mockConstruction(
            TopologyMapper.class,
            (mock, context) -> {
              when(mock.getGlobalVersion()).thenAnswer(invocation -> version);
              when(mock.getEffectiveTopology()).thenAnswer(invocation -> effectiveSystemTopology);
            });
    mockResourceMonitor =
        Mockito.mockConstruction(
            ResourceMonitor.class,
            (mock, context) -> when(mock.getUtilization()).thenReturn(mockUtilization));

    for (int socket = 0; socket < mockShards.length; socket++) {
      int socketId = socket;
      when(baseShard.clone(eq(socketId), any(), any())).thenReturn(mockShards[socketId]);
      when(mockShards[socketId].isStarted()).thenAnswer(invocation -> shardStarted[socketId].get());
      doAnswer(
              invocation -> {
                shardStarted[socketId].set(true);
                return null;
              })
          .when(mockShards[socketId])
          .start(any(), any(), any());
    }

    doAnswer(
            invocation -> {
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
        .start(eq(snapshots[0]), eq(effectiveSystemTopology.socketTopologies().get(0)), any());
    verify(mockShards[1])
        .start(eq(snapshots[1]), eq(effectiveSystemTopology.socketTopologies().get(1)), any());

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

    BitSet retainedSockets = (BitSet) effectiveSystemTopology.effectiveSockets().clone();
    BitSet retainedCores = (BitSet) effectiveSystemTopology.effectiveCores().clone();
    BitSet retainedCpus = (BitSet) effectiveSystemTopology.effectiveCpus().clone();
    retainedSockets.clear(0);
    retainedCores.clear(0, 2);
    retainedCpus.clear(0, 4);

    version = 3;
    effectiveSystemTopology =
        new EffectiveSystemTopology(
            retainedSockets,
            retainedCores,
            retainedCpus,
            effectiveSystemTopology.socketTopologies(),
            version);
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
        .start(eq(snapshots[0]), eq(effectiveSystemTopology.socketTopologies().get(0)), any());
    verify(mockShards[1])
        .start(eq(snapshots[1]), eq(effectiveSystemTopology.socketTopologies().get(1)), any());

    verify(mockShards[0], never()).update(any(), any());
    verify(mockShards[0]).shutDownShard(any());
    verify(mockShards[1]).update(snapshots[1], effectiveSystemTopology.socketTopologies().get(1));

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

    LatticeConfig config =
        new LatticeConfig("TestControlPlane", new BitSet(), Duration.ZERO, baseShard);
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
}
