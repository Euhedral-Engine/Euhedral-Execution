package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.LatticeVertex;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.TopologyMapper.EffectiveSocketTopology;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CpuSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SocketSnapshot;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

@Isolated("Initializes routing statics before thread-local topology mocks")
class ControlPlaneShardLifecycleTest {
    private MockedStatic<SystemInfo> systemInfo;
    private ControlPlaneShard shard;
    private final RecordingClone factory = new RecordingClone(null);
    private final CapturedExecutor executor = new CapturedExecutor();
    private String originalThreadName;

    @BeforeAll
    static void initializeRouting() {
        new LatticeEdge(new AtomicBoolean());
    }

    @BeforeEach
    void setUp() {
        originalThreadName = Thread.currentThread().getName();
        systemInfo = Mockito.mockStatic(SystemInfo.class);
        systemInfo.when(SystemInfo::getMaxCoreId).thenReturn(2);
        systemInfo.when(() -> SystemInfo.fromHexMask("7")).thenReturn(bits(0, 1, 2));
        systemInfo.when(() -> SystemInfo.getSocketInfo(0)).thenReturn(new SocketInfo("7", "7", 0));
    }

    @AfterEach
    void tearDown() {
        try {
            if (shard != null) {
                shard.close();
            }
        } finally {
            executor.shutdownNow();
            systemInfo.close();
            Thread.currentThread().setName(originalThreadName);
        }
    }

    @Test
    void addingSparseCoreResumesDistributorAndEveryActiveCloneAfterDrain() throws Exception {
        start(Duration.ofSeconds(5), 0);
        RecordingClone retained = cloneAt(0);
        update(1, 0, 2);
        RecordingClone added = cloneAt(2);
        assertTrue(shard.isRebalancing());
        assertTrue(shard.coreDistributor.get().getDrainFlag().get());
        assertTrue(added.drain);

        executor.runAll();

        assertFalse(shard.isRebalancing());
        assertFalse(shard.coreDistributor.get().getDrainFlag().get(), "Rebalance must reopen source demand");
        assertFalse(retained.drain);
        assertFalse(added.drain, "New clones must leave their initial drain mode");
        assertSame(retained, cloneAt(0));
        assertSame(added, cloneAt(2));
    }

    @Test
    void timedOutCloneGetsFreshEdgeAndReplacementReceivesFrame() throws Exception {
        start(Duration.ZERO, 0, 2);
        RecordingClone old = cloneAt(2);
        LatticeEdge oldEdge = old.input;
        old.drained = false;
        update(1, 0, 2);
        executor.runAll();

        RecordingClone replacement = cloneAt(2);
        assertNotSame(old, replacement);
        assertNotSame(oldEdge, replacement.input, "Replacement must not inherit the old closed downstream chain");
        assertTrue(oldEdge.isClosed());
        assertFalse(replacement.drain);
        assertEquals(1, old.closes);
        AbstractFrame frame = new AbstractFrame(-1L) {};
        shard.coreDistributor.get().push(frame);
        assertEquals(1, replacement.received.size());
        assertSame(frame, replacement.received.getFirst());
        oldEdge.push(new AbstractFrame(-1L) {});
        oldEdge.close();
        assertTrue(old.received.isEmpty());
        assertEquals(1, replacement.received.size());
        assertFalse(replacement.receiver.isClosed());
    }

    @Test
    void sourceServiceAdmittedBeforeTimeoutReachesReplacementBeforeRestart() throws Exception {
        start(Duration.ZERO, 0);
        RecordingClone old = cloneAt(0);
        old.drained = false;

        CountDownLatch replacementInputEntered = new CountDownLatch(1);
        CountDownLatch allowReplacementInput = new CountDownLatch(1);
        factory.inputEntered = replacementInputEntered;
        factory.allowInput = allowReplacementInput;

        LatticeVertex distributor = shard.coreDistributor.get();
        LatticeVertex.UpstreamInterceptor interceptor = distributor.new UpstreamInterceptor();
        SourceGate source = new SourceGate();
        AbstractFrame frame = new AbstractFrame(0) {};
        source.frame = frame;
        source.addDownstream(interceptor);
        interceptor.addUpstream(source);
        assertTrue(interceptor.acquireLock());

        var sourceExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var replacementExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var request = sourceExecutor.submit(() -> interceptor.request(1));
            assertTrue(source.requestEntered.await(5, TimeUnit.SECONDS));

            update(1, 0);
            executor.tasks.removeFirst().run(); // Retained clone's utilization update.
            var drainTask = executor.tasks.removeFirst();
            var drain = replacementExecutor.submit(drainTask);

            assertTrue(replacementInputEntered.await(5, TimeUnit.SECONDS));
            RecordingClone replacement = cloneAt(0);
            boolean oldClosedBeforeRelease = old.input.isClosed() || old.receiver.isClosed();
            assertTrue(shard.isRebalancing(), "The replacement must still be before final restart");

            source.allowRequest.countDown();
            request.get(5, TimeUnit.SECONDS);

            allowReplacementInput.countDown();
            drain.get(5, TimeUnit.SECONDS);
            assertAll(
                    () -> assertFalse(
                            oldClosedBeforeRelease,
                            "The old receiver must remain live until replacement publication retires its route"),
                    () -> assertEquals(1, old.received.size() + replacement.received.size()),
                    () -> assertTrue(old.received.contains(frame) || replacement.received.contains(frame)),
                    () -> assertTrue(old.input.isClosed()),
                    () -> assertTrue(old.receiver.isClosed()),
                    () -> assertFalse(shard.isRebalancing()));
        } finally {
            source.allowRequest.countDown();
            allowReplacementInput.countDown();
            interceptor.releaseLock();
            if (!interceptor.isComplete()) {
                interceptor.complete();
            }
            sourceExecutor.shutdownNow();
            replacementExecutor.shutdownNow();
            assertTrue(sourceExecutor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(replacementExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void retiredTimeoutCannotReplaceRestartedGenerationAfterCloneCleanup() throws Exception {
        start(Duration.ZERO, 0);
        RecordingClone old = cloneAt(0);
        old.drained = false;
        update(1, 0);
        executor.tasks.removeFirst().run(); // Retained clone's utilization update.
        var cleaned = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var firstDump = new AtomicBoolean(true);
        old.afterDump = () -> {
            if (firstDump.getAndSet(false)) {
                cleaned.countDown();
                try {
                    assertTrue(resume.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
        };
        var runner = java.util.concurrent.Executors.newSingleThreadExecutor();
        var task = executor.tasks.removeFirst();
        var completion = runner.submit(task);
        try {
            assertTrue(cleaned.await(5, TimeUnit.SECONDS));
            assertTrue(old.receiver.isClosed());
            assertEquals(
                    List.of("close", "complete", "dump"), old.events.subList(old.events.size() - 3, old.events.size()));
            var pending = new java.util.concurrent.atomic.AtomicInteger(1);
            shard.shutDownShard(pending);
            executor.runAll();
            assertEquals(0, pending.get(), "Retirement must finish before restart");
            EffectiveSocketTopology next = topology(2, 0);
            shard.start(snapshot(next), next, new LatticeEdge(new AtomicBoolean()));
            RecordingClone fresh = cloneAt(0);
            LatticeEdge freshEdge = fresh.input;
            LatticeVertex freshDistributor = shard.coreDistributor.get();
            CloneableObject[] freshRegistry = shard.clones.get();
            int spawned = factory.created.size();
            resume.countDown();
            completion.get(5, TimeUnit.SECONDS);
            AbstractFrame frame = new AbstractFrame(0) {};
            freshDistributor.push(frame);
            assertAll(
                    () -> assertSame(freshDistributor, shard.coreDistributor.get()),
                    () -> assertSame(freshEdge, shard.coreHandles[0], "Old timeout must not overwrite the fresh edge"),
                    () -> assertFalse(freshEdge.isClosed()),
                    () -> assertSame(freshRegistry, shard.clones.get()),
                    () -> assertSame(fresh, cloneAt(0)),
                    () -> assertEquals(spawned, factory.created.size(), "No unregistered replacement clone may leak"),
                    () -> assertEquals(List.of(frame), fresh.received),
                    () -> assertTrue(old.received.isEmpty()),
                    () -> assertFalse(fresh.receiver.isClosed()),
                    () -> assertFalse(fresh.drain),
                    () -> assertFalse(shard.isRebalancing()),
                    () -> assertEquals(0, shard.coresToDrain.get()));
        } finally {
            resume.countDown();
            runner.shutdownNow();
            assertTrue(runner.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void timedOutShutdownClosesCloneOnceBeforeDumpAndAcknowledgment() throws Exception {
        start(Duration.ZERO, 2);
        RecordingClone old = cloneAt(2);
        old.drained = false;
        old.events.clear();
        java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger(1);
        shard.shutDownShard(pending);
        assertEquals(1, pending.get(), "Queued shutdown must not acknowledge before cleanup");
        executor.runAll();
        assertEquals(1, old.closes, "One terminal owner must invoke close exactly once");
        assertEquals(List.of("drain:true", "close", "complete", "dump"), old.events);
        assertEquals(0, pending.get());
        assertEquals(0, shard.getActiveCores());
    }

    @Test
    void sparseCoreRemoveAndReaddUsesFreshGenerationWithoutRevivingOldClone() throws Exception {
        start(Duration.ofSeconds(5), 0, 2);
        RecordingClone retained = cloneAt(0);
        RecordingClone removed = cloneAt(2);
        LatticeEdge retiredEdge = removed.input;
        removed.events.clear();
        update(1, 0);
        assertTrue(retiredEdge.isClosed());
        assertNull(shard.coreHandles[2]);
        executor.tasks.removeFirst().run(); // Retained clone's utilization update.
        executor.tasks.removeFirst().run(); // First drain acknowledgment is not the last.
        assertTrue(shard.isRebalancing());
        assertTrue(shard.coreDistributor.get().getDrainFlag().get());
        assertTrue(retained.drain);
        executor.runAll();
        assertEquals(1, removed.closes);
        assertEquals(List.of("drain:true", "close", "complete", "dump"), removed.events);
        assertTrue(removed.drain, "Retired clone must never be resumed");
        assertSame(retained, cloneAt(0));

        update(2, 0, 2);
        executor.runAll();
        RecordingClone readded = cloneAt(2);
        assertNotSame(removed, readded);
        assertNotSame(retiredEdge, readded.input);
        assertSame(readded.input, shard.coreHandles[2]);
        assertFalse(readded.drain);
        retiredEdge.push(new AbstractFrame(-1L) {});
        retiredEdge.close();
        assertTrue(removed.received.isEmpty());
        assertTrue(readded.received.isEmpty());
        AbstractFrame frame = new AbstractFrame(-1L) {};
        shard.coreDistributor.get().push(frame);
        assertEquals(1, readded.received.size());
        assertSame(frame, readded.received.getFirst());
        assertTrue(retained.received.isEmpty());
        assertFalse(readded.receiver.isClosed());
    }

    @Test
    void shutdownBeforeFinalDrainAcknowledgmentAllowsShardRestart() throws Exception {
        start(Duration.ofSeconds(5), 0, 2);
        RecordingClone retained = cloneAt(0);
        RecordingClone removed = cloneAt(2);
        LatticeVertex retiredDistributor = shard.coreDistributor.get();
        update(1, 0);
        executor.tasks.removeFirst().run(); // Retained clone's utilization update.
        executor.tasks.removeFirst().run(); // Retained core acknowledges first.
        assertEquals(1, shard.coresToDrain.get());
        assertTrue(shard.isRebalancing());

        var pending = new java.util.concurrent.atomic.AtomicInteger(1);
        shard.shutDownShard(pending);
        executor.runAll(); // Includes the removed core's old final acknowledgment.
        assertAll(
                () -> assertFalse(shard.isRebalancing(), "Retirement must settle the old rebalance"),
                () -> assertEquals(0, shard.coresToDrain.get()),
                () -> assertEquals(0, pending.get()),
                () -> assertNull(shard.coreDistributor.get()),
                () -> assertTrue(retiredDistributor.isClosed()),
                () -> assertTrue(retained.drain),
                () -> assertTrue(removed.drain));

        EffectiveSocketTopology next = topology(2, 0, 2);
        shard.start(snapshot(next), next, new LatticeEdge(new AtomicBoolean()));
        assertEquals(2, shard.getActiveCores(), "Restart must initialize workers, not only an executor");
        assertNotSame(retained, cloneAt(0));
        assertNotSame(removed, cloneAt(2));
        assertTrue(cloneAt(0).started);
        assertTrue(cloneAt(2).started);
        assertFalse(cloneAt(0).drain);
        assertFalse(cloneAt(2).drain);
        assertFalse(shard.isRebalancing());
        assertFalse(shard.coreDistributor.get().getDrainFlag().get());
    }

    @Test
    void retiredAcknowledgmentCannotPublishIntoRestartedShardRebalance() throws Exception {
        start(Duration.ofSeconds(5), 0, 2);
        RecordingClone removed = cloneAt(2);
        update(1, 0);
        executor.tasks.removeFirst().run();
        executor.tasks.removeFirst().run();
        var pending = new java.util.concurrent.atomic.AtomicInteger(1);
        shard.shutDownShard(pending);

        EffectiveSocketTopology next = topology(2, 0, 2);
        shard.start(snapshot(next), next, new LatticeEdge(new AtomicBoolean()));
        assertEquals(2, shard.getActiveCores());
        var original = shard.shardExecutor;
        original.shutdown();
        assertTrue(original.awaitTermination(2, TimeUnit.SECONDS));
        CapturedExecutor restartedExecutor = new CapturedExecutor();
        shard.shardExecutor = restartedExecutor;
        RecordingClone restarted = cloneAt(0);
        LatticeVertex distributor = shard.coreDistributor.get();
        update(3, 0);
        assertEquals(2, shard.coresToDrain.get());

        executor.runAll(); // Old generation acknowledges after a new rebalance has begun.
        assertEquals(0, pending.get());
        assertTrue(removed.drain);
        assertEquals(1, removed.closes);
        assertSame(distributor, shard.coreDistributor.get());
        assertEquals(2, shard.coresToDrain.get(), "Old acknowledgments cannot decrement the new generation");
        assertTrue(shard.isRebalancing());
        assertTrue(distributor.getDrainFlag().get());
        assertTrue(restarted.drain);

        restartedExecutor.runAll();
        assertEquals(0, shard.coresToDrain.get());
        assertFalse(shard.isRebalancing());
        assertFalse(distributor.getDrainFlag().get());
        assertSame(restarted, cloneAt(0));
        assertFalse(restarted.drain);
    }

    @Test
    void removingEveryCoreKeepsEmptyDistributorDrained() throws Exception {
        start(Duration.ofSeconds(5), 0, 2);
        update(1);
        executor.runAll();
        assertFalse(shard.isRebalancing());
        assertEquals(0, shard.getActiveCores());
        assertTrue(shard.coreDistributor.get().getDrainFlag().get());
    }

    private void start(Duration timeout, int... cores) throws Exception {
        shard = new ControlPlaneShard(0, "LifecycleTest", factory, timeout);
        EffectiveSocketTopology topology = topology(0, cores);
        shard.start(snapshot(topology), topology, new LatticeEdge(new AtomicBoolean()));
        var original = shard.shardExecutor;
        original.shutdown();
        assertTrue(original.awaitTermination(2, TimeUnit.SECONDS));
        shard.shardExecutor = executor;
    }

    private void update(int version, int... cores) {
        EffectiveSocketTopology topology = topology(version, cores);
        shard.update(snapshot(topology), topology);
    }

    private RecordingClone cloneAt(int core) {
        return (RecordingClone) shard.clones.get()[core];
    }

    private static BitSet bits(int... ids) {
        BitSet bits = new BitSet();
        for (int id : ids) {
            bits.set(id);
        }
        return bits;
    }

    private static EffectiveSocketTopology topology(int version, int... cores) {
        return new EffectiveSocketTopology(version, 0, bits(cores), bits(cores), List.of(bits(0), bits(1), bits(2)));
    }

    private static SocketSnapshot snapshot(EffectiveSocketTopology topology) {
        CoreSnapshot[] snapshots = new CoreSnapshot[3];
        for (int i = 0; i < snapshots.length; i++) {
            snapshots[i] = new CoreSnapshot(i, 0, 100_000, 0, 0, 0, 0, 0, bits(i), new CpuSnapshot[0]);
        }
        return new SocketSnapshot(0, topology.effectiveCores(), 0, 0, 0, 0, snapshots, 0);
    }

    private static final class SourceGate implements LatticeSource {
        final CountDownLatch requestEntered = new CountDownLatch(1);
        final CountDownLatch allowRequest = new CountDownLatch(1);
        LatticeReceiver downstream;
        AbstractFrame frame;
        boolean complete;

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
            downstream.push(frame);
        }

        @Override
        public void complete() {
            complete = true;
        }

        @Override
        public boolean isComplete() {
            return complete;
        }
    }

    private static final class RecordingClone implements CloneableObject {
        final CloneConfig config;
        final List<RecordingClone> created = new ArrayList<>();
        CountDownLatch updateEntered;
        CountDownLatch allowUpdate;
        CountDownLatch inputEntered;
        CountDownLatch allowInput;
        Runnable afterDump = () -> {};
        final List<AbstractFrame> received = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        LatticeEdge input;
        LatticeVertex receiver;
        boolean drain;
        boolean drained = true;
        boolean started;
        int closes;

        RecordingClone(CloneConfig config) {
            this.config = config;
        }

        @Override
        public RecordingClone clone(CloneConfig config) {
            RecordingClone clone = new RecordingClone(config);
            clone.updateEntered = updateEntered;
            clone.allowUpdate = allowUpdate;
            clone.inputEntered = inputEntered;
            clone.allowInput = allowInput;
            created.add(clone);
            return clone;
        }

        @Override
        public void input(LatticeSource stream) {
            input = (LatticeEdge) stream;
            // Like BaseCloneableObject's fragment, own a downstream vertex, not the input edge.
            receiver = new LatticeVertex("CloneReceiver", 1);
            LatticeEdge terminal = new LatticeEdge(new AtomicBoolean());
            terminal.addDownstream(new LatticeReceiver() {
                public void addUpstream(LatticeSource source) {}

                public void push(AbstractFrame frame) {
                    received.add(frame);
                }

                public void onComplete() {
                    events.add("complete");
                }

                public void onError(Throwable failure) {
                    throw new AssertionError(failure);
                }
            });
            receiver.setDrain(true);
            receiver.setDownstreamMapping(bits(0), new LatticeEdge[] {terminal});
            receiver.addUpstream(input);
            receiver.setDrain(false);
            if (inputEntered != null) {
                inputEntered.countDown();
                try {
                    assertTrue(allowInput.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public boolean isStarted() {
            return started;
        }

        @Override
        public void update(CoreSnapshot snapshot) {
            if (updateEntered != null) {
                updateEntered.countDown();
                try {
                    assertTrue(allowUpdate.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
        }

        @Override
        public boolean isDrained() {
            return drained;
        }

        @Override
        public void setDrainMode(boolean value) {
            drain = value;
            events.add("drain:" + value);
        }

        @Override
        public int getCore() {
            return config.coreId();
        }

        @Override
        public void close() {
            closes++;
            events.add("close");
            receiver.close();
        }

        @Override
        public void dumpLocks() {
            events.add("dump");
            afterDump.run();
        }
    }

    private static final class CapturedExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean shutdown;

        void runAll() {
            int budget = 100;
            while (!tasks.isEmpty()) {
                assertTrue(budget-- > 0, "Unexpected unbounded task submission");
                tasks.removeFirst().run();
            }
        }

        @Override
        public void execute(Runnable task) {
            tasks.addLast(task);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
