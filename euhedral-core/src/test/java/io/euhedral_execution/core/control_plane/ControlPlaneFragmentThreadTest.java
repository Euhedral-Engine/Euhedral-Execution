package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.config.IdlePolicy;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.LatticeVertex;
import io.euhedral_execution.core.flow_control.UpstreamQueue;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.frames.BenchmarkFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.impl.BaseCloneableObject;
import io.euhedral_execution.core.impl.DefaultExecutor;
import io.euhedral_execution.core.ingest.ArrayIngestSink;
import io.euhedral_execution.core.metrics.MetricsAggregator;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.hardware_utils.common.SystemUtilization;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.Mockito;

@Isolated
class ControlPlaneFragmentThreadTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void readinessWaitsForPolicyAndQueueInitialization() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var observer = Mockito.spy(createRecordingObserver());
        var config = Mockito.spy(FragmentConfig.ofBenchmark(observer).clone(cloneConfig()));
        ControlPlaneFragment fragment = new ControlPlaneFragment(config);
        org.mockito.stubbing.Answer<Object> pauseInitialization = invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("initialization gate timed out");
            return invocation.callRealMethod();
        };
        Mockito.doAnswer(pauseInitialization).when(config).idlePolicy();
        try {
            assertFalse(fragment.ready());
            fragment.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(fragment.isStarted());
            assertFalse(fragment.ready(), "thread registration must not publish incomplete worker state");
            release.countDown();
            Awaitility.await().atMost(TIMEOUT).until(fragment::ready);
            for (String fieldName : new String[] {"controlPolicy", "upstreamQueue"}) {
                Field field = ControlPlaneFragment.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                assertNotNull(field.get(fragment), fieldName + " must be visible after readiness");
            }
        } finally {
            release.countDown();
            fragment.close();
            PinnedThreadExecutor.closeAll();
        }
        assertFalse(fragment.ready(), "a closed worker must withdraw readiness");
    }

    @Test
    void latticeFactoryPropagatesIndependentTimingToStartedTrees() throws Exception {
        for (IdlePolicy timing : new IdlePolicy[] {new IdlePolicy(43_000L, 7_000_000L), new IdlePolicy(0L, 29_000L)}) {
            CloneConfig clone = cloneConfigOnCoreIndex(0);
            LatticeConfig lattice = LatticeConfig.ofBenchmark(
                    new UnmodifiableBitSet(clone.effectiveCpus()),
                    createRecordingObserver(),
                    new DefaultExecutor(-1),
                    timing);
            BaseCloneableObject pipeline = (BaseCloneableObject) lattice.baseShard().cloneableObject;
            Field fragmentField = BaseCloneableObject.class.getDeclaredField("fragment");
            fragmentField.setAccessible(true);
            ControlPlaneFragment template = (ControlPlaneFragment) fragmentField.get(pipeline);
            try (ControlPlaneFragment fragment = template.clone(clone)) {
                fragment.start();
                Awaitility.await().atMost(TIMEOUT).until(fragment::ready);
                Field policyField = ControlPlaneFragment.class.getDeclaredField("controlPolicy");
                policyField.setAccessible(true);
                FragmentDecisionTree tree = (FragmentDecisionTree) policyField.get(fragment);
                assertEquals(timing.idleParkNs(), tree.idleParkNs());
                assertEquals(timing.contentionHalfLifeNanos(), tree.contentionHalfLifeNanos());
            } finally {
                template.close();
                lattice.baseShard().close();
                PinnedThreadExecutor.closeAll();
            }
        }
    }

    @Test
    void newestConcurrentSnapshotDeterminesTheAdaptiveBatchCap() throws Exception {
        int cpu = SystemInfo.getCpuSet().nextSetBit(0);
        int core = SystemInfo.getCpuInfo(cpu).core();

        BitSet cpus = new BitSet();
        cpus.set(cpu);
        CloneConfig cloneConfig = new CloneConfig("test", core, cpus);

        try (ControlPlaneFragment fragment =
                new ControlPlaneFragment(FragmentConfig.ofDefaults().clone(cloneConfig))) {
            int threads = 4;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                final long ts = (i + 1) * 1000L;
                final double press = 0.1 * (i + 1);
                executor.submit(() -> {
                    try {
                        latch.await();
                        SystemUtilization.CpuSnapshot cpuSnap = Mockito.mock(SystemUtilization.CpuSnapshot.class);
                        Mockito.when(cpuSnap.pressure()).thenReturn(press);
                        Mockito.when(cpuSnap.lastUsageNs()).thenReturn(ts);

                        SystemUtilization.CpuSnapshot[] cpuArr = new SystemUtilization.CpuSnapshot[cpu + 1];
                        cpuArr[cpu] = cpuSnap;

                        SystemUtilization.CoreSnapshot coreSnap = Mockito.mock(SystemUtilization.CoreSnapshot.class);
                        Mockito.when(coreSnap.cpuSnapshots()).thenReturn(cpuArr);

                        fragment.update(coreSnap);
                    } catch (Exception ignored) {
                    } finally {
                        done.countDown();
                    }
                });
            }

            latch.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
            long eligibleMax = Math.max(2L, Math.min(fragment.getConfig().maxBatchSize(), fragment.getFrameQuota()));
            long expected = Math.round(eligibleMax - 0.4 * (eligibleMax - 2L));
            assertEquals(expected, fragment.getAdaptiveBatchCap());
            executor.close();
        }
    }

    /// Verifies owner-local cached work is delivered before newly available direct upstream work.
    @Test
    void loopAlwaysExecutesLocalCacheFirst() {
        ControlPlaneFragment fragment =
                new ControlPlaneFragment(FragmentConfig.ofDefaults().clone(cloneConfig()));
        LatticeVertex distributor = connect(fragment);
        BenchmarkFrame local = BenchmarkFrame.generate(1, false, 31L, 37L)[0];
        TrackingSource source = new TrackingSource(BenchmarkFrame.generate(1, false, 41L, 43L));
        CountingReceiver receiver = new CountingReceiver();

        try {
            fragment.output().addDownstream(receiver);
            fragment.push(local);
            assertEquals(1L, fragment.getLocalCacheCount());

            fragment.start();
            Awaitility.await().atMost(TIMEOUT).until(fragment::ready);
            distributor.ingest(source);

            Awaitility.await().atMost(TIMEOUT).until(() -> receiver.received.get() == 2);
            assertSame(local, receiver.first.get());
            assertNull(receiver.error.get());
        } finally {
            source.complete();
            fragment.close();
            distributor.close();
            PinnedThreadExecutor.closeAll();
        }
    }

    /// Verifies low-cost unordered work uses direct pulls without issuing eager requests.
    @Test
    void loopDirectlyPullsLowCostWorkWithoutRequesting() {
        ControlPlaneFragment fragment =
                new ControlPlaneFragment(FragmentConfig.ofDefaults().clone(cloneConfig()));
        LatticeVertex distributor = connect(fragment);
        TrackingSource source = new TrackingSource(BenchmarkFrame.generate(8, false, 47L, 53L));
        CountingReceiver receiver = new CountingReceiver();

        try {
            fragment.output().addDownstream(receiver);
            fragment.start();
            Awaitility.await().atMost(TIMEOUT).until(fragment::ready);
            distributor.ingest(source);

            Awaitility.await().atMost(TIMEOUT).until(() -> receiver.received.get() == 8);
            assertEquals(8, source.directFrames.get());
            assertEquals(0, source.requestCalls.get());
            assertNull(receiver.error.get());
        } finally {
            source.complete();
            fragment.close();
            distributor.close();
            PinnedThreadExecutor.closeAll();
        }
    }

    @Test
    void directExecutionKeepsFragmentUndrainedUntilTheTerminalReturns() throws Exception {
        ControlPlaneFragment fragment =
                new ControlPlaneFragment(FragmentConfig.ofDefaults().clone(cloneConfig()));
        LatticeVertex distributor = connect(fragment);
        TrackingSource source = new TrackingSource(BenchmarkFrame.generate(1, false, 83L, 89L));
        BlockingReceiver receiver = new BlockingReceiver();

        try {
            fragment.output().addDownstream(receiver);
            fragment.start();
            Awaitility.await().atMost(TIMEOUT).until(fragment::ready);
            distributor.ingest(source);

            assertTrue(receiver.entered.await(5, TimeUnit.SECONDS));
            assertFalse(fragment.isDrained());
            receiver.release.countDown();
            Awaitility.await().atMost(TIMEOUT).until(fragment::isDrained);
            assertNull(receiver.error.get());
        } finally {
            receiver.release.countDown();
            source.complete();
            fragment.close();
            distributor.close();
            PinnedThreadExecutor.closeAll();
        }
    }

    /// Verifies ordered work that stops direct pulls is requested into the local execution path.
    @Test
    void loopRequestsOrderedWorkAfterDirectPullStops() {
        ControlPlaneFragment fragment =
                new ControlPlaneFragment(FragmentConfig.ofDefaults().clone(cloneConfig()));
        LatticeVertex distributor = connect(fragment);
        TrackingSource source = new TrackingSource(BenchmarkFrame.generate(4, true, 59L, 61L));
        CountingReceiver receiver = new CountingReceiver();

        try {
            fragment.output().addDownstream(receiver);
            fragment.start();
            Awaitility.await().atMost(TIMEOUT).until(fragment::ready);
            distributor.ingest(source);

            Awaitility.await().atMost(TIMEOUT).until(() -> receiver.received.get() == 4);
            assertEquals(0, source.directFrames.get());
            assertTrue(source.requestCalls.get() > 0);
            assertNull(receiver.error.get());
        } finally {
            source.complete();
            fragment.close();
            distributor.close();
            PinnedThreadExecutor.closeAll();
        }
    }

    /// Verifies downstream terminal delay cannot masquerade as executor-body cost.
    @Test
    void loopDoesNotStageForPathExternalTerminalDelay() {
        ControlPlaneFragment fragment =
                new ControlPlaneFragment(FragmentConfig.ofDefaults().clone(cloneConfig()));
        LatticeVertex distributor = connect(fragment);
        TrackingSource source = new TrackingSource(BenchmarkFrame.generate(128, false, 67L, 71L));
        CountingReceiver receiver = new CountingReceiver(50_000L);

        try {
            fragment.output().addDownstream(receiver);
            fragment.start();
            Awaitility.await().atMost(TIMEOUT).until(fragment::ready);
            distributor.ingest(source);

            Awaitility.await().atMost(TIMEOUT).until(() -> receiver.received.get() == 128);
            assertEquals(128, source.directFrames.get());
            assertEquals(0, source.requestCalls.get());
            assertNull(receiver.error.get());
        } finally {
            source.complete();
            fragment.close();
            distributor.close();
            PinnedThreadExecutor.closeAll();
        }
    }

    @Test
    void normalModeReportsPositiveLatencyAndThroughput() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FragmentConfig config =
                FragmentConfig.ofDefaults("fragment-cycle-test", registry).clone(cloneConfig());
        ControlPlaneFragment fragment = new ControlPlaneFragment(config);
        LatticeVertex distributor = connect(fragment);
        ArrayIngestSink sink = new ArrayIngestSink(BenchmarkFrame.generate(64, false, 13L, 17L));
        CountingReceiver receiver = new CountingReceiver();

        try {
            fragment.output().addDownstream(receiver);
            fragment.start();
            Awaitility.await().atMost(TIMEOUT).until(fragment::ready);
            distributor.ingest(sink.getDelegate());

            Awaitility.await().atMost(TIMEOUT).until(() -> receiver.received.get() == 64);

            DistributionSummary latency = registry.find(MetricsAggregator.metricName(
                            "fragment-cycle-test", MetricsAggregator.LATENCY_SUMMARY_SUFFIX))
                    .summary();
            DistributionSummary throughput = registry.find(MetricsAggregator.metricName(
                            "fragment-cycle-test", MetricsAggregator.THROUGHPUT_SUMMARY_SUFFIX))
                    .summary();
            Awaitility.await().atMost(TIMEOUT).until(() -> latency.count() > 0 && throughput.count() > 0);

            assertTrue(Double.isFinite(latency.totalAmount()));
            assertTrue(latency.totalAmount() > 0);
            assertTrue(Double.isFinite(throughput.totalAmount()));
            assertTrue(throughput.totalAmount() > 0);
            assertNull(receiver.error.get());
        } finally {
            sink.complete();
            fragment.close();
            distributor.close();
            registry.close();
            PinnedThreadExecutor.closeAll();
        }
    }

    private static FragmentObserver createRecordingObserver() {
        return new FragmentObserver() {

            @Override
            protected void batchProgressState(
                    int core,
                    int socket,
                    long cycleEpoch,
                    long batchEpoch,
                    long upstreamCount,
                    int registeredWorkers,
                    long productiveHandleCount,
                    int workerRank,
                    long contention,
                    double avgServiceTime) {}

            @Override
            protected void batchCompleteState(
                    int core,
                    int socket,
                    long cycleEpoch,
                    long batchEpoch,
                    long upstreamCount,
                    int registeredWorkers,
                    long productiveHandleCount,
                    int workerRank,
                    long contention,
                    double avgServiceTime,
                    double throughput) {}
        };
    }

    /// Returns a clone configuration for the first CPU available to the test process.
    private static CloneConfig cloneConfig() {
        return cloneConfigOnCoreIndex(0);
    }

    private static CloneConfig cloneConfigOnCoreIndex(int coreIndex) {
        int selectedCpu = workerCpus().get(coreIndex);
        BitSet cpus = new BitSet();
        cpus.set(selectedCpu);
        return new CloneConfig(
                "fragment-cycle-test", SystemInfo.getCpuInfo(selectedCpu).core(), cpus);
    }

    private static void requireTwoWorkerCores() {
        // Check before allocating fragments or changing the JVM-wide forced-participation property.
        assumeTrue(
                workerCpus().size() >= 2,
                "Requires two distinct physical cores; two SMT CPUs on one core share a worker rank and upstream"
                        + " partition");
    }

    private static List<Integer> workerCpus() {
        BitSet available = new BitSet();
        available.or(SystemInfo.getCpuSet());
        available.and(ThreadTools.BASE_MASK);
        return workerCpus(available, cpu -> SystemInfo.getCpuInfo(cpu).core(), SystemInfo.getPCoreSet());
    }

    private static List<Integer> workerCpus(BitSet cpus, IntUnaryOperator coreOfCpu, BitSet performanceCores) {
        Map<Integer, Integer> firstCpuByCore = new TreeMap<>();
        for (int cpu = cpus.nextSetBit(0); cpu >= 0; cpu = cpus.nextSetBit(cpu + 1)) {
            firstCpuByCore.putIfAbsent(coreOfCpu.applyAsInt(cpu), cpu);
        }
        // Match LatticeEdge's ranking: performance cpus first, then ascending cpu IDs.
        return firstCpuByCore.keySet().stream()
                .sorted(Comparator.<Integer>comparingInt(core -> performanceCores.get(core) ? 0 : 1)
                        .thenComparingInt(Integer::intValue))
                .map(firstCpuByCore::get)
                .toList();
    }

    @Test
    void workerSelectionDoesNotTreatSmtSiblingsAsIndependentCores() {
        BitSet cpus = new BitSet();
        cpus.set(0, 2);
        assertEquals(List.of(0), workerCpus(cpus, cpu -> 0, new BitSet()));
        assertEquals(List.of(0, 1), workerCpus(cpus, cpu -> cpu, new BitSet()));
    }

    @Test
    void workerSelectionDeduplicatesNonAdjacentSiblingsAndMatchesCoreRanks() {
        BitSet cpus = new BitSet();
        cpus.set(0, 4);
        BitSet performanceCores = new BitSet();
        performanceCores.set(1);
        assertEquals(List.of(1, 0), workerCpus(cpus, cpu -> cpu % 2, performanceCores));
    }

    /// Connects the fragment behind the cached single-route topology used in production.
    private static LatticeVertex connect(ControlPlaneFragment... fragments) {
        TestDistributor.resetSharedRoutingState();
        TestDistributor distributor = new TestDistributor(fragments.length);
        BitSet active = new BitSet(fragments.length);
        LatticeEdge[] handles = new LatticeEdge[fragments.length];
        for (int i = 0; i < fragments.length; i++) {
            active.set(i);
            handles[i] = new LatticeEdge(distributor.getDrainFlag());
        }

        distributor.setDrain(true);
        assertTrue(distributor.setDownstreamMapping(active, handles));
        distributor.setDrain(false);
        for (int i = 0; i < fragments.length; i++) {
            fragments[i].input(handles[i]);
        }
        return distributor;
    }

    private static final class TestDistributor extends LatticeVertex {

        private static String ranks() {
            return CPU_RANK.getAcquire().toString();
        }

        private TestDistributor(int downstreamCount) {
            super("fragment-cycle-test", downstreamCount, RoutingFunction.DEFAULT);
        }

        /// Restores the isolated test's JVM-wide upstream registry to an empty state.
        private static void resetSharedRoutingState() {
            UpstreamQueue.UP_QUEUE.remove();
            UPSTREAM_COUNT.set(0L);
            CPU_COUNT.set(0L);
            for (int i = 0; i < UPSTREAMS.length; i++) {
                if (UPSTREAMS[i] != null) {
                    UPSTREAMS[i].clear();
                }
                ACTIVE_PARTITIONS.set(i, 0L);
            }
            Int2IntOpenHashMap initialRanks = new Int2IntOpenHashMap(UPSTREAMS.length);
            initialRanks.defaultReturnValue(-1);
            CPU_RANK.set(initialRanks);
        }
    }

    private static final class TrackingSource implements LatticeSource {

        private final AbstractFrame[] frames;
        private final AtomicInteger index = new AtomicInteger();
        private final AtomicBoolean complete = new AtomicBoolean();
        private final AtomicReference<LatticeReceiver> downstream = new AtomicReference<>();
        private final AtomicInteger directFrames = new AtomicInteger();
        private final AtomicInteger requestCalls = new AtomicInteger();

        /// Creates a deterministic pull/request source over the supplied frame array.
        private TrackingSource(AbstractFrame[] frames) {
            this.frames = frames;
        }

        @Override
        public void addDownstream(LatticeReceiver receiver) {
            if (!this.downstream.compareAndSet(null, receiver)) {
                receiver.onError(new IllegalStateException("Tracking source already has a downstream"));
            }
        }

        @Override
        public long pull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            long pulled = 0L;
            while (pulled < demand) {
                int next = this.index.get();
                if (next >= this.frames.length) {
                    break;
                }
                AbstractFrame frame = this.frames[next];
                if (stopCondition.apply(frame)) {
                    break;
                }
                if (!this.index.compareAndSet(next, next + 1)) {
                    continue;
                }
                consumer.accept(frame);
                this.directFrames.incrementAndGet();
                pulled++;
            }
            completeWhenExhausted();
            return pulled;
        }

        @Override
        public void request(long demand) {
            this.requestCalls.incrementAndGet();
            LatticeReceiver receiver = this.downstream.get();
            long pushed = 0L;
            while (receiver != null && pushed < demand) {
                int next = this.index.getAndIncrement();
                if (next >= this.frames.length) {
                    this.index.set(this.frames.length);
                    break;
                }
                receiver.push(this.frames[next]);
                pushed++;
            }
            completeWhenExhausted();
        }

        @Override
        public void complete() {
            if (this.complete.compareAndSet(false, true)) {
                LatticeReceiver receiver = this.downstream.getAndSet(null);
                if (receiver != null) {
                    receiver.onComplete();
                }
            }
        }

        @Override
        public boolean isComplete() {
            return this.complete.get();
        }

        /// Completes exactly once after the last array element has been claimed.
        private void completeWhenExhausted() {
            if (this.index.get() >= this.frames.length) {
                complete();
            }
        }
    }

    private static final class CountingReceiver implements LatticeReceiver {

        private final long delayNanos;
        private final AtomicInteger received = new AtomicInteger();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final AtomicReference<AbstractFrame> first = new AtomicReference<>();

        /// Creates a receiver that records frames without an artificial service delay.
        private CountingReceiver() {
            this(0L);
        }

        /// Creates a receiver whose `push` contributes `delayNanos` to measured service time.
        private CountingReceiver(long delayNanos) {
            this.delayNanos = delayNanos;
        }

        @Override
        public void push(AbstractFrame frame) {
            if (this.delayNanos > 0L) {
                LockSupport.parkNanos(this.delayNanos);
            }
            this.first.compareAndSet(null, frame);
            this.received.incrementAndGet();
        }

        @Override
        public void onComplete() {}

        @Override
        public void onError(Throwable throwable) {
            this.error.set(throwable);
        }

        @Override
        public void addUpstream(LatticeSource upstream) {}
    }

    private static final class BlockingReceiver implements LatticeReceiver {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void push(AbstractFrame frame) {
            this.entered.countDown();
            try {
                if (!this.release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("terminal release timed out");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("terminal interrupted", failure);
            }
        }

        @Override
        public void onComplete() {}

        @Override
        public void onError(Throwable throwable) {
            this.error.set(throwable);
        }

        @Override
        public void addUpstream(LatticeSource upstream) {}
    }
}
