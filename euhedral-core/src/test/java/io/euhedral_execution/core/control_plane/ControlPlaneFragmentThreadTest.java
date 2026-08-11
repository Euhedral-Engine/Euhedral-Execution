package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentActionPicker;
import io.euhedral_execution.core.config.FragmentConfig;
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
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.common.SystemUtilization;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Arrays;
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
    void shouldRunWhenTheCpuExecutorWasCreatedWithRegularThreads() throws Exception {
        int cpu = SystemInfo.getCpuSet().nextSetBit(0);
        int core = SystemInfo.getCpuInfo(cpu).core();

        PinnedThreadExecutor previous = PinnedThreadExecutor.get(cpu);
        if (previous != null) {
            previous.close();
        }

        PinnedThreadExecutor executor =
                PinnedThreadExecutor.getOrSetIfAbsent(cpu, "regular-thread-owner", Thread.NORM_PRIORITY, true);
        assertFalse(executor.submit(() -> Thread.currentThread() instanceof FlowThread)
                .get(1, TimeUnit.SECONDS));

        BitSet cpus = new BitSet();
        cpus.set(cpu);
        CloneConfig cloneConfig = new CloneConfig("test", core, cpus);
        FragmentActionPicker halted = new FragmentActionPicker(new double[28]);
        ControlPlaneFragment fragment =
                new ControlPlaneFragment(FragmentConfig.ofBenchmark(halted).clone(cloneConfig));

        try {
            fragment.start();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));

            assertTrue(fragment.isStarted());
        } finally {
            fragment.close();
            executor.close();
        }
    }

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

    @Test
    void normalModeDoesNotConsultTheActionPicker() {
        FailOnUseActionPicker actionPicker = new FailOnUseActionPicker();
        FragmentConfig defaults = FragmentConfig.ofDefaults();
        FragmentConfig config = new FragmentConfig(
                        null,
                        defaults.cacheConfig(),
                        actionPicker,
                        defaults.maxBatchSize(),
                        false,
                        defaults.metricPrefix(),
                        defaults.registry())
                .clone(cloneConfig());

        BenchmarkFrame ordered = new BenchmarkFrame(1L);
        BenchmarkFrame unordered = new BenchmarkFrame(2L);
        unordered.randomizeHash(3L);
        ArrayIngestSink sink = new ArrayIngestSink(new AbstractFrame[] {ordered, unordered});
        CountingReceiver receiver = new CountingReceiver();
        ControlPlaneFragment fragment = new ControlPlaneFragment(config);
        LatticeVertex distributor = connect(fragment);

        try {
            fragment.output().addDownstream(receiver);
            fragment.start();
            Awaitility.await().atMost(TIMEOUT).until(fragment::ready);

            distributor.ingest(sink.getDelegate());

            Awaitility.await().atMost(TIMEOUT).until(() -> receiver.received.get() == 2);
            assertNull(receiver.error.get());
        } finally {
            sink.complete();
            fragment.close();
            distributor.close();
            PinnedThreadExecutor.closeAll();
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

    /// Returns a clone configuration for the first CPU available to the test process.
    private static CloneConfig cloneConfig() {
        int cpu = SystemInfo.getCpuSet().nextSetBit(0);
        if (cpu < 0) {
            throw new IllegalStateException("No CPU is available for the unit test");
        }

        BitSet cpus = new BitSet();
        cpus.set(cpu);
        return new CloneConfig("fragment-cycle-test", SystemInfo.getCpuInfo(cpu).core(), cpus);
    }

    /// Connects the fragment behind the cached single-route topology used in production.
    private static LatticeVertex connect(ControlPlaneFragment fragment) {
        TestDistributor.resetSharedRoutingState();
        LatticeVertex distributor = new TestDistributor();
        LatticeEdge handle = new LatticeEdge(distributor.getDrainFlag());
        BitSet active = new BitSet(1);
        active.set(0);

        distributor.setDrain(true);
        assertTrue(distributor.setDownstreamMapping(active, new LatticeEdge[] {handle}));
        distributor.setDrain(false);
        fragment.input(handle);
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
            THREAD_COUNT.set(0L);
            for (int i = 0; i < UPSTREAMS.length; i++) {
                if (UPSTREAMS[i] != null) {
                    UPSTREAMS[i].clear();
                }
                ACTIVE_PARTITIONS.set(i, 0L);
            }
        }
    }

    private static final class FailOnUseActionPicker extends FragmentActionPicker {

        private FailOnUseActionPicker() {
            super(rejectingWeights());
        }

        @Override
        public boolean halted() {
            throw new IllegalStateException("Normal mode consulted the action picker");
        }

        @Override
        public void normalize(double[] inputs) {
            throw new IllegalStateException("Normal mode consulted the action picker");
        }

        @Override
        public boolean performAction(Action action, double[] inputs) {
            throw new IllegalStateException("Normal mode consulted the action picker");
        }

        private static double[] rejectingWeights() {
            double[] weights = new double[28];
            Arrays.fill(weights, -1.0);
            return weights;
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
