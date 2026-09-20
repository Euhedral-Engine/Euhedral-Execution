package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.LatticeVertex;
import io.euhedral_execution.core.flow_control.RoutingPolicy;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CpuSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SocketSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import test_utils.TestFrame;

@Isolated
class ShardRoutingGenerationTest {

    private MockedStatic<SystemInfo> systemInfo;
    private ControlPlaneShard shard;

    @BeforeAll
    static void initializeRoutingStatics() {
        new LatticeEdge(new java.util.concurrent.atomic.AtomicBoolean());
    }

    @BeforeEach
    void setUp() {
        systemInfo = Mockito.mockStatic(SystemInfo.class);
        systemInfo.when(SystemInfo::getMaxCoreId).thenReturn(4);
        SocketInfo socketInfo = Mockito.mock(SocketInfo.class);
        Mockito.when(socketInfo.getCoreSet()).thenReturn(bits(1, 2, 4));
        systemInfo.when(() -> SystemInfo.getSocketInfo(0)).thenReturn(socketInfo);
    }

    @AfterEach
    void tearDown() {
        try {
            if (shard != null) {
                shard.close();
            }
        } finally {
            systemInfo.close();
        }
    }

    @Test
    void hotRemapKeepsAnInFlightRouteOnOneGeneration() throws Exception {
        RecordingClone factory = new RecordingClone();
        GateShard gatedShard = new GateShard(factory);
        shard = gatedShard;
        EffectiveSocketTopology oldTopology = topology(0, 1, 4);
        shard.start(
                snapshot(oldTopology), oldTopology, new LatticeEdge(new java.util.concurrent.atomic.AtomicBoolean()));

        RecordingClone oldCore4 = factory.cloneAt(4);
        TestFrame frame = new TestFrame("hot-remap");
        frame.setRoutingPolicy(RoutingPolicy.CACHE_LOCAL);
        frame.setOrigin(new CpuInfo(4, 4, 0));

        ExecutorService racers = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Void> producer =
                    CompletableFuture.runAsync(() -> shard.coreDistributor.get().push(frame), racers);
            assertTrue(gatedShard.routeEntered.await(5, TimeUnit.SECONDS));

            LatticeVertex distributor = shard.coreDistributor.get();
            distributor.setDrain(true);
            shard.coreHandles[2] = new LatticeEdge(distributor.getDrainFlag());
            assertTrue(distributor.setDownstreamMapping(bits(1, 2, 4), shard.coreHandles));

            gatedShard.allowRoute.countDown();
            producer.get(5, TimeUnit.SECONDS);

            assertEquals(List.of(frame), oldCore4.receiver.frames);
            assertEquals(1, oldCore4.receiver.frames.size());
            assertSame(frame, oldCore4.receiver.frames.getFirst());
            assertEquals(1, factory.totalDelivered());
        } finally {
            gatedShard.allowRoute.countDown();
            racers.shutdownNow();
            assertTrue(racers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void remapDefersRetiredReceiverCloseUntilInFlightRouteCompletes() throws Exception {
        RecordingClone factory = new RecordingClone();
        GateShard gatedShard = new GateShard(factory);
        shard = gatedShard;
        EffectiveSocketTopology oldTopology = topology(0, 1, 4);
        shard.start(
                snapshot(oldTopology), oldTopology, new LatticeEdge(new java.util.concurrent.atomic.AtomicBoolean()));
        RecordingClone oldCore4 = factory.cloneAt(4);
        LatticeVertex distributor = shard.coreDistributor.get();

        LatticeEdge newCore2Handle = new LatticeEdge(distributor.getDrainFlag());
        RecordingReceiver newCore2 = new RecordingReceiver();
        newCore2Handle.addDownstream(newCore2);
        shard.coreHandles[2] = newCore2Handle;
        TestFrame oldFrame = new TestFrame("retired-route");
        oldFrame.setRoutingPolicy(RoutingPolicy.CACHE_LOCAL);
        oldFrame.setOrigin(new CpuInfo(4, 4, 0));
        ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Void> producer =
                    CompletableFuture.runAsync(() -> distributor.push(oldFrame), producerExecutor);
            assertTrue(gatedShard.routeEntered.await(5, TimeUnit.SECONDS));

            distributor.setDrain(true);
            assertTrue(distributor.setDownstreamMapping(bits(1, 2), shard.coreHandles));
            assertFalse(((LatticeEdge) oldCore4.input).isClosed());

            gatedShard.allowRoute.countDown();
            producer.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(oldFrame), oldCore4.receiver.frames);
            assertTrue(((LatticeEdge) oldCore4.input).isClosed());

            distributor.setDrain(false);
            TestFrame newFrame = new TestFrame("new-route");
            newFrame.setRoutingPolicy(RoutingPolicy.CACHE_LOCAL);
            newFrame.setOrigin(new CpuInfo(2, 2, 0));
            distributor.push(newFrame);
            assertEquals(List.of(newFrame), newCore2.frames);
            assertEquals(2, oldCore4.receiver.frames.size() + newCore2.frames.size());
        } finally {
            gatedShard.allowRoute.countDown();
            producerExecutor.shutdownNow();
            assertTrue(producerExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void retiredSocketWaitsForInFlightGlobalRouteBeforeClosingShardReceiver() throws Exception {
        RecordingClone factory = new RecordingClone();
        CountDownLatch routeEntered = new CountDownLatch(1);
        CountDownLatch allowRoute = new CountDownLatch(1);
        try (LatticeVertex global = new LatticeVertex("GlobalGenerationTest", 2, (frame, mapSize, state) -> {
            routeEntered.countDown();
            try {
                assertTrue(allowRoute.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return 0;
        })) {
            LatticeEdge oldSocket = new LatticeEdge(global.getDrainFlag());
            shard = new ControlPlaneShard(0, "RetiredSocketTest", factory, Duration.ofSeconds(5));
            EffectiveSocketTopology topology = topology(0, 1, 4);
            shard.start(snapshot(topology), topology, oldSocket);
            LatticeVertex retiredCoreDistributor = shard.coreDistributor.get();

            LatticeEdge[] socketHandles = new LatticeEdge[] {oldSocket, new LatticeEdge(global.getDrainFlag())};
            global.setDrain(true);
            assertTrue(global.setDownstreamMapping(bits(0), socketHandles));
            global.setDrain(false);

            TestFrame frame = new TestFrame("retired-socket-route");
            LatticeVertex.UpstreamInterceptor interceptor = global.new UpstreamInterceptor();
            SourceGate source = new SourceGate();
            source.frame = frame;
            source.addDownstream(interceptor);
            interceptor.addUpstream(source);
            assertTrue(interceptor.acquireLock());
            source.allowRequest.countDown();
            ExecutorService producerExecutor = Executors.newSingleThreadExecutor();
            try {
                CompletableFuture<Void> producer =
                        CompletableFuture.runAsync(() -> interceptor.request(1), producerExecutor);
                assertTrue(source.requestEntered.await(5, TimeUnit.SECONDS));
                assertTrue(routeEntered.await(5, TimeUnit.SECONDS));

                global.setDrain(true);
                assertTrue(global.setDownstreamMapping(bits(1), socketHandles));
                AtomicInteger pendingShutdowns = new AtomicInteger(1);
                shard.shutDownShard(pendingShutdowns);
                boolean receiverClosedBeforeRelease = retiredCoreDistributor.isClosed();

                allowRoute.countDown();
                producer.get(5, TimeUnit.SECONDS);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (pendingShutdowns.get() != 0 && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }

                assertFalse(
                        receiverClosedBeforeRelease,
                        "Retired shard receivers must remain live until the admitted global route returns");
                assertEquals(List.of(frame), factory.cloneAt(1).receiver.frames);
                assertEquals(1, factory.totalDelivered());
                assertEquals(0, pendingShutdowns.get());
                assertTrue(retiredCoreDistributor.isClosed());

                global.close();
                FinalizedFrame rejected = new FinalizedFrame("asynchronous-after-global-close");
                interceptor.push(rejected);
                assertEquals(1, rejected.finalizations.get());
                assertTrue(rejected.failure.get() instanceof IllegalStateException);
                assertEquals(1, factory.totalDelivered());
            } finally {
                allowRoute.countDown();
                interceptor.releaseLock();
                if (!interceptor.isComplete()) {
                    interceptor.complete();
                }
                producerExecutor.shutdownNow();
                assertTrue(producerExecutor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void sourceServiceOverlapDeliversOnceToTheAdmittedGeneration() throws Exception {
        RecordingClone factory = new RecordingClone();
        shard = new ControlPlaneShard(0, "SourceOverlapShard", factory, Duration.ofSeconds(5));
        EffectiveSocketTopology oldTopology = topology(0, 1, 4);
        shard.start(
                snapshot(oldTopology), oldTopology, new LatticeEdge(new java.util.concurrent.atomic.AtomicBoolean()));

        LatticeVertex distributor = shard.coreDistributor.get();
        LatticeVertex.UpstreamInterceptor interceptor = distributor.new UpstreamInterceptor();
        SourceGate source = new SourceGate();
        TestFrame frame = new TestFrame("source-overlap");
        frame.setRoutingPolicy(RoutingPolicy.CACHE_LOCAL);
        frame.setOrigin(new CpuInfo(4, 4, 0));
        source.frame = frame;
        source.addDownstream(interceptor);
        interceptor.addUpstream(source);
        assertTrue(interceptor.acquireLock());

        RecordingReceiver newCore2 = new RecordingReceiver();
        ExecutorService requester = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Void> request = CompletableFuture.runAsync(() -> interceptor.request(1), requester);
            assertTrue(source.requestEntered.await(5, TimeUnit.SECONDS));

            distributor.setDrain(true);
            LatticeEdge newHandle = new LatticeEdge(distributor.getDrainFlag());
            newHandle.addDownstream(newCore2);
            shard.coreHandles[2] = newHandle;
            assertTrue(distributor.setDownstreamMapping(bits(1, 2, 4), shard.coreHandles));

            source.allowRequest.countDown();
            request.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(frame), factory.cloneAt(4).receiver.frames);
            assertTrue(newCore2.frames.isEmpty());
            assertEquals(1, factory.totalDelivered());
        } finally {
            source.allowRequest.countDown();
            interceptor.releaseLock();
            if (!interceptor.isComplete()) {
                interceptor.complete();
            }
            requester.shutdownNow();
            assertTrue(requester.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static EffectiveSocketTopology topology(int version, int... cores) {
        BitSet active = bits(cores);
        List<BitSet> coreToCpu = new ArrayList<>(5);
        for (int core = 0; core < 5; core++) {
            coreToCpu.add(bits(core));
        }
        return new EffectiveSocketTopology(version, 0, active, (BitSet) active.clone(), coreToCpu);
    }

    private static SocketSnapshot snapshot(EffectiveSocketTopology topology) {
        CoreSnapshot[] coreSnapshots = new CoreSnapshot[5];
        for (int core = 0; core < coreSnapshots.length; core++) {
            coreSnapshots[core] = new CoreSnapshot(
                    core,
                    0,
                    100_000,
                    0,
                    0,
                    0,
                    0,
                    0,
                    topology.effectiveCoreToCpu().get(core),
                    new CpuSnapshot[0]);
        }
        return new SocketSnapshot(0, topology.effectiveCores(), 0, 0, 0, 0, coreSnapshots, 0);
    }

    private static BitSet bits(int... values) {
        BitSet bits = new BitSet();
        for (int value : values) {
            bits.set(value);
        }
        return bits;
    }

    private static final class GateShard extends ControlPlaneShard {

        private final CountDownLatch routeEntered = new CountDownLatch(1);
        private final CountDownLatch allowRoute = new CountDownLatch(1);

        private GateShard(CloneableObject cloneableObject) {
            super(0, "GenerationTestShard", cloneableObject, Duration.ofSeconds(5));
        }

        @Override
        protected int route(AbstractFrame frame, int mapSize, LatticeVertex.RoutingState routeState) {
            routeEntered.countDown();
            try {
                assertTrue(allowRoute.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return super.route(frame, mapSize, routeState);
        }
    }

    private static final class RecordingClone implements CloneableObject {

        private final List<RecordingClone> created;
        private final int core;
        private LatticeSource input;
        private final RecordingReceiver receiver = new RecordingReceiver();

        private RecordingClone() {
            this(-1, new ArrayList<>());
        }

        private RecordingClone(int core, List<RecordingClone> created) {
            this.core = core;
            this.created = created;
        }

        @Override
        public RecordingClone clone(CloneConfig config) {
            RecordingClone clone = new RecordingClone(config.coreId(), this.created);
            this.created.add(clone);
            return clone;
        }

        @Override
        public void input(LatticeSource stream) {
            this.input = stream;
            stream.addDownstream(this.receiver);
        }

        @Override
        public int getCore() {
            return this.core;
        }

        private RecordingClone cloneAt(int core) {
            return this.created.stream()
                    .filter(clone -> clone.core == core)
                    .reduce((first, second) -> second)
                    .orElseThrow();
        }

        private int totalDelivered() {
            return this.created.stream()
                    .mapToInt(clone -> clone.receiver.frames.size())
                    .sum();
        }
    }

    private static final class SourceGate implements LatticeSource {

        private final CountDownLatch requestEntered = new CountDownLatch(1);
        private final CountDownLatch allowRequest = new CountDownLatch(1);
        private LatticeReceiver downstream;
        private AbstractFrame frame;
        private boolean complete;

        @Override
        public void addDownstream(LatticeReceiver downstream) {
            this.downstream = downstream;
        }

        @Override
        public long pull(
                java.util.function.Consumer<AbstractFrame> consumer,
                java.util.function.Function<AbstractFrame, Boolean> stopCondition,
                long demand) {
            return 0;
        }

        @Override
        public void request(long demand) {
            requestEntered.countDown();
            try {
                assertTrue(allowRequest.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            this.downstream.push(this.frame);
        }

        @Override
        public void complete() {
            this.complete = true;
        }

        @Override
        public boolean isComplete() {
            return this.complete;
        }
    }

    private static final class FinalizedFrame extends TestFrame {
        private final AtomicInteger finalizations = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private FinalizedFrame(String value) {
            super(value);
        }

        @Override
        public void doFinallyWithError(Throwable throwable) {
            this.failure.set(throwable);
            this.finalizations.incrementAndGet();
        }
    }

    private static final class RecordingReceiver implements LatticeReceiver {

        private final List<AbstractFrame> frames = new ArrayList<>();

        @Override
        public void push(AbstractFrame frame) {
            this.frames.add(frame);
        }

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void onComplete() {}

        @Override
        public void addUpstream(LatticeSource upstream) {}
    }
}
