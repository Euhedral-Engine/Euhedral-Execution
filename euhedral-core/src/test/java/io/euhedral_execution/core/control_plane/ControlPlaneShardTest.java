package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CpuSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SocketSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class ControlPlaneShardTest {

    private MockedStatic<SystemInfo> mockSysInfo;
    private ControlPlaneShard shard;

    @BeforeAll
    static void initializeSharedRoutingStateFromTheRealTopology() {
        new LatticeEdge(new AtomicBoolean());
    }

    private static SocketSnapshot getSocketSnapshot(EffectiveSocketTopology topology) {
        CoreSnapshot[] coreSnapshots =
                new CoreSnapshot[topology.effectiveCores().length()];
        for (int i = 0; i < coreSnapshots.length; i++) {
            coreSnapshots[i] = new CoreSnapshot(
                    i, 0, 100_000, 0, 0, 0, 0, 0, topology.effectiveCoreToCpu().get(i), new CpuSnapshot[0]);
        }

        return new SocketSnapshot(0, topology.effectiveCores(), 0, 0, 0, 0, coreSnapshots, 0);
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

    private static CloneConfig[] getConfigs(SocketSnapshot snapshot, EffectiveSocketTopology topology) {
        CloneConfig[] configs = new CloneConfig[topology.effectiveCores().cardinality()];
        for (int i = 0; i < configs.length; i++) {
            configs[i] = new CloneConfig("TestShard", i, snapshot.coreSnapshots()[i].effectiveCpus());
        }
        return configs;
    }

    @BeforeEach
    void setUp() {
        mockSysInfo = Mockito.mockStatic(SystemInfo.class);
        mockSysInfo.when(SystemInfo::getMaxCoreId).thenReturn(1);
        BitSet cores = new BitSet(2);
        cores.set(0, 2);
        mockSysInfo.when(() -> SystemInfo.fromHexMask("3")).thenReturn(cores);
        mockSysInfo.when(() -> SystemInfo.getSocketInfo(0)).thenReturn(new SocketInfo("f", "3", 0));
        mockSysInfo.when(() -> SystemInfo.socketL3Cache(0)).thenReturn(0L);
    }

    @AfterEach
    void tearDown() {
        try {
            if (shard != null) {
                shard.close();
            }
        } finally {
            if (mockSysInfo != null) {
                mockSysInfo.close();
            }
        }
    }

    @Test
    void startsAClonePerEffectiveCore() {
        RecordingClone factory = new RecordingClone();
        LatticeEdge upstream = new LatticeEdge(new AtomicBoolean());
        shard = new ControlPlaneShard(0, "TestShard", factory, Duration.ZERO);

        EffectiveSocketTopology topology = getTopology();
        SocketSnapshot snapshot = getSocketSnapshot(topology);
        CloneConfig[] configs = getConfigs(snapshot, topology);

        shard.start(snapshot, topology, upstream);

        assertEquals(configs.length, factory.created.size());
        for (int i = 0; i < configs.length; i++) {
            RecordingClone clone = factory.created.get(i);
            CloneConfig config = configs[i];

            assertEquals(config, clone.config);
            assertTrue(clone.input instanceof LatticeEdge);
            assertSame(snapshot.coreSnapshots()[config.coreId()], clone.snapshot);
            assertTrue(clone.started);
            assertFalse(clone.drainMode);
        }

        assertTrue(shard.isStarted(), "Expected the shard to be marked started");
    }

    @Test
    void shutdownClosesEveryCloneAndAcknowledgesOnce() {
        RecordingClone factory = new RecordingClone();
        shard = new ControlPlaneShard(0, "TestShard", factory, Duration.ZERO);
        EffectiveSocketTopology topology = getTopology();
        shard.start(getSocketSnapshot(topology), topology, new LatticeEdge(new AtomicBoolean()));
        AtomicInteger shutdownsRemaining = new AtomicInteger(1);

        shard.shutDownShard(shutdownsRemaining);

        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> shutdownsRemaining.get() == 0);
        assertFalse(shard.isStarted());
        assertEquals(0, shard.getActiveCores());
        assertTrue(factory.created.stream().allMatch(clone -> clone.closed));
    }

    @Test
    void unstartedShardAcknowledgesShutdownImmediately() {
        shard = new ControlPlaneShard(0, "TestShard", new RecordingClone(), Duration.ZERO);
        AtomicInteger shutdownsRemaining = new AtomicInteger(1);

        shard.shutDownShard(shutdownsRemaining);

        assertEquals(0, shutdownsRemaining.get());
    }

    @Test
    void closesOnlyClonesRemovedByARebalance() {
        LatticeEdge upstream = new LatticeEdge(new AtomicBoolean());
        RecordingClone factory = new RecordingClone();
        shard = new ControlPlaneShard(0, "TestShard", factory, Duration.ofSeconds(1));

        EffectiveSocketTopology topo1 = getTopology(); // Version 0, Core 0 and 1 active

        SocketSnapshot snapshot1 = getSocketSnapshot(topo1);

        shard.start(snapshot1, topo1, upstream);

        RecordingClone first = factory.created.get(0);
        RecordingClone second = factory.created.get(1);
        assertTrue(first.started);
        assertTrue(second.started);

        // Trigger Rebalance: Drop Core 0
        BitSet retainedCores = (BitSet) topo1.effectiveCores().clone();
        BitSet retainedCpus = (BitSet) topo1.effectiveCpus().clone();
        List<BitSet> retainedCoreToCpu = new ArrayList<>();
        for (BitSet coreCpus : topo1.effectiveCoreToCpu()) {
            retainedCoreToCpu.add((BitSet) coreCpus.clone());
        }
        retainedCores.clear(0);
        retainedCpus.clear(0, 2);
        retainedCoreToCpu.get(0).clear();
        EffectiveSocketTopology topo2 = new EffectiveSocketTopology(
                topo1.version() + 1, topo1.socketId(), retainedCores, retainedCpus, retainedCoreToCpu);

        SocketSnapshot snap2 = getSocketSnapshot(topo2);

        shard.update(snap2, topo2);

        Awaitility.await().atMost(2, TimeUnit.SECONDS).until(() -> !shard.isRebalancing());

        assertTrue(first.closed, "The removed core should be closed");
        assertFalse(second.closed, "The retained core should stay open");
        assertEquals(1, shard.getActiveCores());
    }

    private static final class RecordingClone implements CloneableObject {

        private final List<RecordingClone> created;
        private final CloneConfig config;

        private LatticeSource input;
        private CoreSnapshot snapshot;
        private volatile boolean drainMode;
        private volatile boolean started;
        private volatile boolean closed;

        private RecordingClone() {
            this(new ArrayList<>(), null);
        }

        private RecordingClone(List<RecordingClone> created, CloneConfig config) {
            this.created = created;
            this.config = config;
        }

        @Override
        public RecordingClone clone(CloneConfig cloneConfig) {
            RecordingClone clone = new RecordingClone(this.created, cloneConfig);
            this.created.add(clone);
            return clone;
        }

        @Override
        public void input(LatticeSource stream) {
            this.input = stream;
        }

        @Override
        public void update(CoreSnapshot coreSnapshot) {
            this.snapshot = coreSnapshot;
        }

        @Override
        public void setDrainMode(boolean value) {
            this.drainMode = value;
        }

        @Override
        public void start() {
            this.started = true;
        }

        @Override
        public boolean isStarted() {
            return this.started;
        }

        @Override
        public boolean ready() {
            return this.started;
        }

        @Override
        public boolean isDrained() {
            return true;
        }

        @Override
        public int getCore() {
            return this.config == null ? -1 : this.config.coreId();
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }
}
