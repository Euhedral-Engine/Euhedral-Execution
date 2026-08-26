package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.LatticeVertex;
import io.euhedral_execution.core.flow_control.RoutingPolicy;
import io.euhedral_execution.core.flow_control.UpstreamQueue;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.frames.BenchmarkFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.ingest.ArrayIngestSink;
import io.euhedral_execution.core.metrics.MetricsAggregator;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.common.SystemUtilization;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.time.Duration;
import java.util.BitSet;
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
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.Mockito;

@Isolated
class ControlPlaneFragmentThreadTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    void shouldLinearizeConcurrentSnapshotUpdatesLockFree() throws Exception {
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
            assertTrue(fragment.getAdaptiveBatchCap() >= 2L);
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

    @Test
    void forcedCacheWorkerExecutesLocalCacheAndDoesNotPullUpstream() {
        System.setProperty(FragmentControlConfig.FORCED_ACTIVE_PARTICIPANT_COUNT, "1");
        System.setProperty(FragmentControlConfig.CACHE_PARK_NS, "50000");

        ControlPlaneFragment fragment1 =
                new ControlPlaneFragment(FragmentConfig.ofBenchmark(createRecordingObserver(), FragmentDecisionWeights.DEFAULT)
                        .clone(cloneConfigOnCoreIndex(0)));
        ControlPlaneFragment fragment2 =
                new ControlPlaneFragment(FragmentConfig.ofBenchmark(createRecordingObserver(), FragmentDecisionWeights.DEFAULT)
                        .clone(cloneConfigOnCoreIndex(1)));
        LatticeVertex distributor = connect(fragment1, fragment2);

        BenchmarkFrame local = BenchmarkFrame.generate(1, false, 31L, 37L)[0];
        TrackingSource source = new TrackingSource(BenchmarkFrame.generate(16, false, 41L, 43L));
        CountingReceiver receiver1 = new CountingReceiver();
        CountingReceiver receiver2 = new CountingReceiver();

        try {
            fragment1.output().addDownstream(receiver1);
            fragment2.output().addDownstream(receiver2);

            // Preload a frame into fragment2's local cache
            fragment2.push(local);
            assertEquals(1L, fragment2.getLocalCacheCount());

            fragment1.start();
            fragment2.start();
            Awaitility.await().atMost(TIMEOUT).until(() -> fragment1.ready() && fragment2.ready());

            // Fragment 2 (forced CACHE) must execute its preloaded local cache frame
            Awaitility.await().atMost(TIMEOUT).until(() -> receiver2.received.get() >= 1);
            assertSame(local, receiver2.first.get());

            distributor.ingest(source);

            // Fragment 1 (rank 1 <= 1) should pull upstream work
            Awaitility.await().atMost(TIMEOUT).until(() -> receiver1.received.get() > 0);

            // Upstream tracking source was pulled directly by fragment1 without requests from CACHE
            assertEquals(0, source.requestCalls.get());
            assertNull(receiver1.error.get());
            assertNull(receiver2.error.get());
        } finally {
            source.complete();
            fragment1.close();
            fragment2.close();
            distributor.close();
            PinnedThreadExecutor.closeAll();
            System.clearProperty(FragmentControlConfig.FORCED_ACTIVE_PARTICIPANT_COUNT);
            System.clearProperty(FragmentControlConfig.CACHE_PARK_NS);
        }
    }

    @Test
    void forcedCacheWorkerExecutesRemoteCachedFrame() {
        System.setProperty(FragmentControlConfig.FORCED_ACTIVE_PARTICIPANT_COUNT, "1");

        ControlPlaneFragment fragment1 =
                new ControlPlaneFragment(FragmentConfig.ofBenchmark(createRecordingObserver(), FragmentDecisionWeights.DEFAULT)
                        .clone(cloneConfigOnCoreIndex(0)));
        ControlPlaneFragment fragment2 =
                new ControlPlaneFragment(FragmentConfig.ofBenchmark(createRecordingObserver(), FragmentDecisionWeights.DEFAULT)
                        .clone(cloneConfigOnCoreIndex(1)));
        LatticeVertex distributor = connect(fragment1, fragment2);

        BenchmarkFrame frame = BenchmarkFrame.generate(1, false, 79L, 83L)[0];
        CountingReceiver receiver2 = new CountingReceiver();

        try {
            fragment2.output().addDownstream(receiver2);

            // Push a frame into the distributor's downstream handle 1 (parent cache queue for fragment 2)
            fragment2.input(new LatticeSource() {
                @Override
                public void addDownstream(LatticeReceiver receiver) {
                    receiver.push(frame);
                }

                @Override
                public long pull(Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
                    return 0;
                }

                @Override
                public void request(long demand) {}

                @Override
                public void complete() {}

                @Override
                public boolean isComplete() {
                    return false;
                }
            });

            fragment1.start();
            fragment2.start();
            Awaitility.await().atMost(TIMEOUT).until(() -> fragment1.ready() && fragment2.ready());

            // Fragment 2 executes work drained from its remote cache
            Awaitility.await().atMost(TIMEOUT).until(() -> receiver2.received.get() >= 1);
            assertSame(frame, receiver2.first.get());
            assertNull(receiver2.error.get());
        } finally {
            fragment1.close();
            fragment2.close();
            distributor.close();
            PinnedThreadExecutor.closeAll();
            System.clearProperty(FragmentControlConfig.FORCED_ACTIVE_PARTICIPANT_COUNT);
        }
    }

    @Test
    void forcedCacheParkDurationIsObservableAndResetSafe() {
        System.setProperty(FragmentControlConfig.FORCED_ACTIVE_PARTICIPANT_COUNT, "1");
        System.setProperty(FragmentControlConfig.CACHE_PARK_NS, "10000");

        ControlPlaneFragment fragment1 =
                new ControlPlaneFragment(FragmentConfig.ofBenchmark(createRecordingObserver(), FragmentDecisionWeights.DEFAULT)
                        .clone(cloneConfigOnCoreIndex(0)));
        ControlPlaneFragment fragment2 =
                new ControlPlaneFragment(FragmentConfig.ofBenchmark(createRecordingObserver(), FragmentDecisionWeights.DEFAULT)
                        .clone(cloneConfigOnCoreIndex(1)));

        try (fragment1; fragment2; LatticeVertex ignored = connect(fragment1, fragment2)) {
            fragment1.start();
            fragment2.start();
            Awaitility.await().atMost(TIMEOUT).until(() -> fragment1.ready() && fragment2.ready());

            // Reset fragment2 while it is in the forced CACHE loop
            long deadline = System.nanoTime() + 1_000_000_000L;
            assertEquals(0L, fragment2.reset(deadline));
            assertTrue(fragment2.ready());
        } finally {
            PinnedThreadExecutor.closeAll();
            System.clearProperty(FragmentControlConfig.FORCED_ACTIVE_PARTICIPANT_COUNT);
            System.clearProperty(FragmentControlConfig.CACHE_PARK_NS);
        }
    }

    @Test
    void contentionStalenessRecordsExactDecisionInputs() {
        System.setProperty(FragmentControlConfig.FORCED_ACTIVE_PARTICIPANT_COUNT, "1");

        AtomicBoolean recorded = new AtomicBoolean(false);
        AtomicInteger observedPath = new AtomicInteger(-1);
        AtomicInteger observedRank = new AtomicInteger(-1);
        AtomicInteger observedWorkers = new AtomicInteger(-1);

        FragmentObserver observer = new FragmentObserver() {
            @Override
            public boolean observesContentionStaleness() {
                return true;
            }

            @Override
            protected void cycleStartState(int core, int socket, long cycleEpoch, long batchEpoch, long completed, long batchSize, long upstreamCount, int registeredWorkers, long productiveHandleCount, int workerRank, long contention, double throughput) {}

            @Override
            protected void batchProgressState(int core, int socket, long cycleEpoch, long batchEpoch, long upstreamCount, int registeredWorkers, long productiveHandleCount, int workerRank, long contention, double avgServiceTime) {}

            @Override
            protected void batchCompleteState(int core, int socket, long cycleEpoch, long batchEpoch, long upstreamCount, int registeredWorkers, long productiveHandleCount, int workerRank, long contention, double avgServiceTime, double throughput) {}

            @Override
            protected void rawBodyCost(int core, int socket, long cycleEpoch, long batchEpoch, long rawBodyCost) {}

            @Override
            protected void idleBranchDecision(int core, int socket, long cycleEpoch, long batchEpoch, int contentionPolicy, int bodyPolicy, long contention, double smoothedBodyCost) {}

            @Override
            protected void execBranchDecision(int core, int socket, long cycleEpoch, long batchEpoch, int contentionPolicy, int bodyPolicy, long contention, double smoothedBodyCost) {}

            @Override
            protected void contentionStalenessState(
                    int core, int socket, long cycleEpoch, long batchEpoch, long measuredContention,
                    long lastRawContention, long contentionObservationCount, long lastContentionObservationNs,
                    long cyclesSinceContentionObservation, long nanosSinceContentionObservation,
                    long consecutiveIdleDecisions, long idleDurationSelectedNs, long successfulAcquisitionCount,
                    long failedAcquisitionCount, long totalAcquisitionAttempts, int executionPath,
                    long localCacheCount, long productiveHandleCount, int registeredWorkers, int workerRank,
                    boolean productivityExcluded, long productivityExclusionCount, long productivityThresholdNs,
                    double smoothedBodyCostNs, boolean bodyHistoryReady) {
                if (workerRank == 2) {
                    observedPath.set(executionPath);
                    observedRank.set(workerRank);
                    observedWorkers.set(registeredWorkers);
                    recorded.set(true);
                }
            }
        };

        ControlPlaneFragment fragment1 =
                new ControlPlaneFragment(FragmentConfig.ofBenchmark(observer, FragmentDecisionWeights.DEFAULT)
                        .clone(cloneConfigOnCoreIndex(0)));
        ControlPlaneFragment fragment2 =
                new ControlPlaneFragment(FragmentConfig.ofBenchmark(observer, FragmentDecisionWeights.DEFAULT)
                        .clone(cloneConfigOnCoreIndex(1)));
        LatticeVertex distributor = connect(fragment1, fragment2);

        // Preload 2 frames to complete a batch on fragment 2 and update registeredWorkers at boundary
        BenchmarkFrame[] frames = BenchmarkFrame.generate(2, false, 11L, 13L);
        fragment2.push(frames[0]);
        fragment2.push(frames[1]);

        try {
            fragment1.start();
            fragment2.start();
            Awaitility.await().atMost(TIMEOUT).until(() -> fragment1.ready() && fragment2.ready());
            Awaitility.await().atMost(TIMEOUT).until(recorded::get);

            assertEquals(FragmentControlConfig.ExecutionPath.CACHE.ordinal(), observedPath.get());
            assertEquals(2, observedRank.get());
            Awaitility.await().atMost(TIMEOUT).until(() -> observedWorkers.get() >= 2);
        } finally {
            fragment1.close();
            fragment2.close();
            distributor.close();
            PinnedThreadExecutor.closeAll();
            System.clearProperty(FragmentControlConfig.FORCED_ACTIVE_PARTICIPANT_COUNT);
        }
    }

    private static FragmentObserver createRecordingObserver() {
        return new FragmentObserver() {
            @Override
            protected void cycleStartState(int core, int socket, long cycleEpoch, long batchEpoch, long completed, long batchSize, long upstreamCount, int registeredWorkers, long productiveHandleCount, int workerRank, long contention, double throughput) {}

            @Override
            protected void batchProgressState(int core, int socket, long cycleEpoch, long batchEpoch, long upstreamCount, int registeredWorkers, long productiveHandleCount, int workerRank, long contention, double avgServiceTime) {}

            @Override
            protected void batchCompleteState(int core, int socket, long cycleEpoch, long batchEpoch, long upstreamCount, int registeredWorkers, long productiveHandleCount, int workerRank, long contention, double avgServiceTime, double throughput) {}

            @Override
            protected void rawBodyCost(int core, int socket, long cycleEpoch, long batchEpoch, long rawBodyCost) {}

            @Override
            protected void idleBranchDecision(int core, int socket, long cycleEpoch, long batchEpoch, int contentionPolicy, int bodyPolicy, long contention, double smoothedBodyCost) {}

            @Override
            protected void execBranchDecision(int core, int socket, long cycleEpoch, long batchEpoch, int contentionPolicy, int bodyPolicy, long contention, double smoothedBodyCost) {}
        };
    }

    /// Returns a clone configuration for the first CPU available to the test process.
    private static CloneConfig cloneConfig() {
        return cloneConfigOnCoreIndex(0);
    }

    private static CloneConfig cloneConfigOnCoreIndex(int coreIndex) {
        int cpu = SystemInfo.getCpuSet().nextSetBit(0);
        int seenCores = 0;
        int selectedCpu = -1;
        int lastCore = -1;
        while (cpu >= 0) {
            int core = SystemInfo.getCpuInfo(cpu).core();
            if (core != lastCore) {
                if (seenCores == coreIndex) {
                    selectedCpu = cpu;
                    break;
                }
                seenCores++;
                lastCore = core;
            }
            cpu = SystemInfo.getCpuSet().nextSetBit(cpu + 1);
        }
        if (selectedCpu < 0) {
            cpu = SystemInfo.getCpuSet().nextSetBit(0);
            for (int i = 0; i < coreIndex; i++) {
                cpu = SystemInfo.getCpuSet().nextSetBit(cpu + 1);
            }
            if (cpu < 0) {
                throw new IllegalStateException("CPU index " + coreIndex + " is not available");
            }
            selectedCpu = cpu;
        }
        BitSet cpus = new BitSet();
        cpus.set(selectedCpu);
        return new CloneConfig("fragment-cycle-test", SystemInfo.getCpuInfo(selectedCpu).core(), cpus);
    }

    /// Connects the fragment behind the cached single-route topology used in production.
    private static LatticeVertex connect(ControlPlaneFragment... fragments) {
        TestDistributor.resetSharedRoutingState();
        TestDistributor distributor = new TestDistributor();
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

        private TestDistributor() {
            super("fragment-cycle-test", 1, RoutingFunction.DEFAULT, 256, RoutingPolicy.ANYWHERE);
        }

        /// Restores the isolated test's JVM-wide upstream registry to an empty state.
        private static void resetSharedRoutingState() {
            UpstreamQueue.UP_QUEUE.remove();
            UPSTREAM_COUNT.set(0L);
            CORE_COUNT.set(0L);
            for (int i = 0; i < UPSTREAMS.length; i++) {
                if (UPSTREAMS[i] != null) {
                    UPSTREAMS[i].clear();
                }
                ACTIVE_PARTITIONS.set(i, 0L);
            }
            Int2IntOpenHashMap initialRanks = new Int2IntOpenHashMap(UPSTREAMS.length);
            initialRanks.defaultReturnValue(-1);
            CORE_RANK.set(initialRanks);
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
}
