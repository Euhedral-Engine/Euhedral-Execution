package euhedral.io.control_plane;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import euhedral.io.flow_control.FluxEdge;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.resource_monitoring.NumaMapper.SocketTopology;
import euhedral.io.resource_monitoring.ResourceMonitor;
import euhedral.io.resource_monitoring.SystemUtilization.CoreSnapshot;
import euhedral.io.resource_monitoring.SystemUtilization.SocketSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.BitSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Flux;

class ControlPlaneShardTest {

    private final ResourceMonitor mockResourceMonitor = mock(ResourceMonitor.class);
    private final MeterRegistry mockMeterRegistry = mock(MeterRegistry.class);

    @Test
    public void testInitialization() {
        TestClone clone = mock(TestClone.class);
        FluxEdge upstream = mock(FluxEdge.class);
        AbstractFrame frame = mock(AbstractFrame.class);

        doReturn(clone).when(clone).clone(any(CloneConfig.class));
        when(clone.output()).thenAnswer(_ -> Flux.just(frame));

        ControlPlaneShard shard = new ControlPlaneShard(1, "TestShard", clone,
                mockResourceMonitor,
                mockMeterRegistry);

        SocketTopology topology = getTopology();
        SocketSnapshot snapshot = getNodeSnapshot(topology);
        CloneConfig[] configs = getConfigs(snapshot, topology, mockResourceMonitor,
                mockMeterRegistry);

        shard.start(snapshot, topology, upstream);

        verify(upstream).subscribe(any(Subscriber.class));
        verify(clone, times(configs.length)).clone(any(CloneConfig.class));
        verify(clone, times(configs.length)).ingest(any(FluxEdge.class));
        verify(clone, times(configs.length)).output();
        verify(clone, times(configs.length)).output();
        verify(clone, times(configs.length)).setDrainMode(true);
        verify(clone, times(configs.length)).update(any(CoreSnapshot.class));
        verify(clone, times(configs.length)).start();

        for (CloneConfig config : configs) {
            verify(clone).clone(config);
            verify(clone).update(snapshot.coreSnapshots()[config.coreId()]);
        }

        assertTrue(shard.isStarted(), "Expected the shard to be marked started");
    }

    private static SocketTopology getTopology() {
        AtomicInteger version = new AtomicInteger(0);
        AtomicReference<BitSet> effectiveCores = new AtomicReference<>(new BitSet(2));
        AtomicReference<BitSet> effectiveCpus = new AtomicReference<>(new BitSet(4));
        AtomicReference<BitSet[]> effectiveCoreToCpu = new AtomicReference<>(
                new BitSet[]{new BitSet(4), new BitSet(4)});

        effectiveCores.get().set(0, 2);
        effectiveCpus.get().set(0, 4);
        BitSet[] core2cpu = effectiveCoreToCpu.get();
        core2cpu[0].set(0);
        core2cpu[0].set(1);
        core2cpu[1].set(2);
        core2cpu[1].set(3);
        return new SocketTopology(version, 0, effectiveCores, effectiveCpus, effectiveCoreToCpu);
    }

    private static SocketSnapshot getNodeSnapshot(SocketTopology topology) {
        CoreSnapshot[] coreSnapshots = new CoreSnapshot[topology.effectiveCores().get().length()];
        for (int i = 0; i < coreSnapshots.length; i++) {
            coreSnapshots[i] = new CoreSnapshot(i, 0, 100_000, 0, 0, 0, 0, 0,
                    topology.effectiveCoreToCpu().get()[i],
                    null, true);
        }

        return new SocketSnapshot(0, topology.effectiveCores().get(), null, 0, 0, 0, 0, coreSnapshots, 0);
    }

    private static CloneConfig[] getConfigs(SocketSnapshot snapshot, SocketTopology topology,
            ResourceMonitor resourceMonitor, MeterRegistry meterRegistry) {
        CloneConfig[] configs = new CloneConfig[topology.effectiveCores().get().cardinality()];
        for (int i = 0; i < configs.length; i++) {
            configs[i] = new CloneConfig("TestShard", i, snapshot.coreSnapshots()[i].quotaCpus(),
                    snapshot.coreSnapshots()[i].effectiveCpus(),
                    resourceMonitor,
                    meterRegistry, "TestShard");
        }
        return configs;
    }

    @Test
    public void testRebalanceOnTopologyChange() throws Exception {
        FluxEdge upstream = mock(FluxEdge.class);
        TestClone baseClone = mock(TestClone.class);

        TestClone[] clones = new TestClone[2];
        clones[0] = mock(TestClone.class);
        clones[1] = mock(TestClone.class);

        AbstractFrame frame = mock(AbstractFrame.class);

        final int[] idx = new int[]{0};
        when(baseClone.clone(any(CloneConfig.class))).thenAnswer(_ -> clones[idx[0]++]);
        when(clones[0].output()).thenAnswer(_ -> Flux.just(frame));
        when(clones[0].isStarted()).thenReturn(true);
        when(clones[1].output()).thenAnswer(_ -> Flux.just(frame));
        when(clones[1].isStarted()).thenReturn(true);

        ControlPlaneShard shard = new ControlPlaneShard(1, "TestShard", baseClone,
                mockResourceMonitor,
                mockMeterRegistry);

        SocketTopology topo1 = getTopology(); // Version 0, Core 0 and 1 active
        shard.start(getNodeSnapshot(topo1), topo1, upstream);

        verify(clones[0]).start();
        verify(clones[1]).start();

        // Trigger Rebalance: Drop Core 0
        SocketTopology topo2 = getTopology();
        topo2.version().incrementAndGet();
        topo2.effectiveCores().get().clear(0);
        topo2.effectiveCpus().get().clear(0, 2);
        topo2.effectiveCoreToCpu().get()[0].clear();

        SocketSnapshot snap2 = getNodeSnapshot(topo2);

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

    private static final class TestClone implements CloneableObject {

        @Override
        public TestClone clone(CloneConfig cloneConfig) {
            return new TestClone();
        }

        @Override
        public void close() throws Exception {

        }
    }
}