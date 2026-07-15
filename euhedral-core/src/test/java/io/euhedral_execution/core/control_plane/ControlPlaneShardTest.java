package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SocketSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class ControlPlaneShardTest {

    private MockedStatic<SystemInfo> mockSysInfo;

    @BeforeEach
    void setUp() {
        mockSysInfo = Mockito.mockStatic(SystemInfo.class);
    }

    @AfterEach
    void tearDown() {
        mockSysInfo.close();
    }

    @Test
    void testInitialization() {
        TestClone clone = mock(TestClone.class);
        LatticeEdge upstream = spy(new LatticeEdge(new AtomicBoolean()));

        doReturn(clone).when(clone).clone(any(CloneConfig.class));

        mockSysInfo.when(SystemInfo::getMaxCoreId).thenReturn(1);
        ControlPlaneShard shard = new ControlPlaneShard(1, "TestShard", clone, Duration.ZERO);

        EffectiveSocketTopology topology = getTopology();
        SocketSnapshot snapshot = getSocketSnapshot(topology);
        SocketInfo info = mock(SocketInfo.class);
        CloneConfig[] configs = getConfigs(snapshot, topology);

        mockSysInfo.when(() -> SystemInfo.getSocketInfo(snapshot.socketId())).thenReturn(info);
        when(info.getCoreSet()).thenReturn(new BitSet());

        shard.start(snapshot, topology, upstream);

        verify(clone, times(configs.length)).clone(any(CloneConfig.class));
        verify(clone, times(configs.length)).input(any(LatticeEdge.class));
        verify(clone, times(configs.length)).setDrainMode(true);
        verify(clone, times(configs.length)).update(any(CoreSnapshot.class));
        verify(clone, times(configs.length)).start();

        for (CloneConfig config : configs) {
            verify(clone).clone(config);
            verify(clone).update(snapshot.coreSnapshots()[config.coreId()]);
        }

        assertTrue(shard.isStarted(), "Expected the shard to be marked started");
    }

    @Test
    void testRebalanceOnTopologyChange() throws Exception {
        LatticeEdge upstream = spy(new LatticeEdge(new AtomicBoolean()));
        TestClone baseClone = mock(TestClone.class);

        TestClone[] clones = new TestClone[2];
        clones[0] = mock(TestClone.class);
        clones[1] = mock(TestClone.class);

        final int[] idx = new int[]{0};
        when(baseClone.clone(any(CloneConfig.class))).thenAnswer(c -> clones[idx[0]++]);
        when(clones[0].isStarted()).thenReturn(true);
        when(clones[1].isStarted()).thenReturn(true);

        mockSysInfo.when(SystemInfo::getMaxCoreId).thenReturn(1);
        ControlPlaneShard shard = new ControlPlaneShard(1, "TestShard", baseClone, Duration.ofMinutes(1));

        EffectiveSocketTopology topo1 = getTopology(); // Version 0, Core 0 and 1 active

        SocketInfo info = mock(SocketInfo.class);
        SocketSnapshot snapshot1 = getSocketSnapshot(topo1);
        mockSysInfo.when(() -> SystemInfo.getSocketInfo(snapshot1.socketId())).thenReturn(info);
        when(info.getCoreSet()).thenReturn(new BitSet());

        shard.start(getSocketSnapshot(topo1), topo1, upstream);

        verify(clones[0]).start();
        verify(clones[1]).start();

        // Trigger Rebalance: Drop Core 0
        topo1.effectiveCores().clear(0);
        topo1.effectiveCpus().clear(0, 2);
        topo1.effectiveCoreToCpu().get(0).clear();
        EffectiveSocketTopology topo2 = new EffectiveSocketTopology(topo1.version() + 1,
                topo1.socketId(),
                topo1.effectiveCores(), topo1.effectiveCpus(), topo1.effectiveCoreToCpu());

        SocketSnapshot snap2 = getSocketSnapshot(topo2);

        when(clones[0].isDrained()).thenReturn(true);
        when(clones[0].getCore()).thenReturn(0);

        when(clones[1].isDrained()).thenReturn(true);
        when(clones[1].getCore()).thenReturn(1);

        shard.update(snap2, topo2);

        verify(clones[0], times(2)).setDrainMode(true);

        // Wait for Async logic in drainAndPruneClones
        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .until(() -> !shard.isRebalancing());

        // Old core should have been dropped
        verify(clones[0], times(1)).close();
        verify(clones[1], times(0)).close();
    }

    private static EffectiveSocketTopology getTopology() {
        BitSet effectiveCores = new BitSet(2);
        BitSet effectiveCpus = new BitSet(4);
        List<BitSet> effectiveCoreToCpu = new ArrayList<>();
        effectiveCoreToCpu.add(new BitSet(4));
        effectiveCoreToCpu.add(new BitSet(4));

        effectiveCores.set(0, 2);
        effectiveCpus.set(0, 4);
        effectiveCoreToCpu.get(0).set(0, 2);
        effectiveCoreToCpu.get(1).set(2, 4);
        return new EffectiveSocketTopology(0, 0, effectiveCores, effectiveCpus, effectiveCoreToCpu);
    }

    private static SocketSnapshot getSocketSnapshot(EffectiveSocketTopology topology) {
        CoreSnapshot[] coreSnapshots = new CoreSnapshot[topology.effectiveCores().length()];
        for (int i = 0; i < coreSnapshots.length; i++) {
            coreSnapshots[i] = new CoreSnapshot(i, 0, 100_000, 0, 0, 0, 0, 0,
                    topology.effectiveCoreToCpu().get(i),
                    null);
        }

        return new SocketSnapshot(0, topology.effectiveCores(), 0, 0, 0, 0, coreSnapshots, 0);
    }

    private static CloneConfig[] getConfigs(SocketSnapshot snapshot,
            EffectiveSocketTopology topology) {
        CloneConfig[] configs = new CloneConfig[topology.effectiveCores().cardinality()];
        for (int i = 0; i < configs.length; i++) {
            configs[i] = new CloneConfig("TestShard", i,
                    snapshot.coreSnapshots()[i].effectiveCpus());
        }
        return configs;
    }

    private static final class TestClone implements CloneableObject {

        @Override
        public TestClone clone(CloneConfig cloneConfig) {
            return new TestClone();
        }
    }
}