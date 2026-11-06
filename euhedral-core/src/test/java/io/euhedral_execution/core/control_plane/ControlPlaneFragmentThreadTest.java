package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.CloneLivenessRegistry;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.config.IdlePolicy;
import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.LatticeVertex;
import io.euhedral_execution.core.flow_control.UpstreamQueue;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.frames.BenchmarkFrame;
import io.euhedral_execution.core.frames.DummyFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.impl.BaseCloneableObject;
import io.euhedral_execution.core.impl.DefaultExecutor;
import io.euhedral_execution.core.ingest.ArrayIngestSink;
import io.euhedral_execution.core.metrics.MetricsAggregator;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.hardware_utils.affinity.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.affinity.ThreadTools;
import io.euhedral_execution.hardware_utils.monitor.SystemUtilization;
import io.euhedral_execution.hardware_utils.topology.SystemInfo;
import io.euhedral_execution.hardware_utils.util.UnmodifiableBitSet;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
        var slot = config.cloneConfig()
                .livenessRegistry()
                .slot(config.cloneConfig().effectiveCpus().nextSetBit(0));
        Mockito.doAnswer(pauseInitialization).when(config).idlePolicy();
        try {
            assertFalse(fragment.ready());
            fragment.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(fragment.isStarted());
            assertFalse(fragment.ready(), "thread registration must not publish incomplete worker state");
            var worker = config.cloneConfig().livenessRegistry().workers()[0];
            assertTrue(worker.running());
            assertNotNull(worker.thread());
            assertSame(worker.thread(), slot.thread());
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
            awaitWorkersAndCloseExecutors(fragment);
        }
        assertFalse(fragment.ready(), "a closed worker must withdraw readiness");
        Awaitility.await().atMost(TIMEOUT).until(() -> !slot.running());
        assertFalse(slot.running());
        assertNull(slot.thread());
    }

    @Test
    void closeLogsAndContinuesWhenTheWorkerThreadRemainsRunning() throws Exception {
        CloneConfig clone = cloneConfig();
        ControlPlaneFragment fragment =
                new ControlPlaneFragment(FragmentConfig.ofDefaults().clone(clone));
        InterruptIgnoringReceiver receiver = new InterruptIgnoringReceiver();
        fragment.output().addDownstream(receiver);
        fragment.push(DummyFrame.INSTANCE);
        fragment.start();
        assertTrue(receiver.entered.await(5, TimeUnit.SECONDS));

        try {
            fragment.close();
            assertTrue(clone.livenessRegistry().workers()[0].running());
        } finally {
            receiver.release.countDown();
            fragment.close();
            awaitWorkersAndCloseExecutors(fragment);
        }
    }

    @Test
    void exceptionalWorkerExitClearsThePublishedLivenessRecord() {
        CloneConfig clone = cloneConfig();
        FragmentConfig config = Mockito.spy(FragmentConfig.ofDefaults().clone(clone));
        AtomicBoolean failed = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
                    failed.set(true);
                    throw new IllegalStateException("deliberate worker failure");
                })
                .when(config)
                .idlePolicy();
        ControlPlaneFragment fragment = new ControlPlaneFragment(config);
        var slot = clone.livenessRegistry().slot(clone.effectiveCpus().nextSetBit(0));
        try {
            fragment.start();
            Awaitility.await().atMost(TIMEOUT).until(failed::get);
            Awaitility.await().atMost(TIMEOUT).until(() -> !slot.running());
            assertNull(slot.thread());
        } finally {
            fragment.close();
            awaitWorkersAndCloseExecutors(fragment);
        }
    }

    @Test
    void livenessRegistryIndexesSparsePhysicalCpuIds() {
        BitSet cpus = new BitSet();
        cpus.set(2);
        cpus.set(9);

        CloneLivenessRegistry registry = new CloneLivenessRegistry(cpus);

        assertNull(registry.slot(0));
        assertNotNull(registry.slot(2));
        assertNotNull(registry.slot(9));
        assertNull(registry.slot(10));
        assertEquals(2, registry.workers().length);
    }

    @Test
    void cloneConfigRejectsALivenessRegistryForDifferentCpus() {
        CloneConfig clone = cloneConfig();
        assertThrows(
                IllegalArgumentException.class,
                () -> new CloneConfig(
                        clone.shardName(),
                        clone.coreId(),
                        clone.effectiveCpus(),
                        new CloneLivenessRegistry(new BitSet())));
    }

    @Test
    void smtBuddyPublishesAndRetiresItsOwnWorkerSlot() throws Exception {
        BitSet available = SystemInfo.getCpuSet();
        int primaryCpu = available.nextSetBit(0);
        int buddyCpu = available.nextSetBit(primaryCpu + 1);
        assumeTrue(buddyCpu >= 0, "Requires two available logical CPUs");

        BitSet cpus = new BitSet();
        cpus.set(primaryCpu);
        cpus.set(buddyCpu);
        CloneConfig clone = new CloneConfig(
                "smt-liveness-test", SystemInfo.getCpuInfo(primaryCpu).core(), cpus);
        FragmentConfig base = FragmentConfig.ofDefaults().clone(clone);
        FragmentConfig config = new FragmentConfig(
                base.cloneConfig(),
                base.cacheConfig(),
                base.observer(),
                base.maxBatchSize(),
                true,
                base.idlePolicy(),
                base.benchmarkMode(),
                base.metricPrefix(),
                base.registry());
        ControlPlaneFragment fragment = new ControlPlaneFragment(config);
        try {
            fragment.start();
            Awaitility.await().atMost(TIMEOUT).until(fragment::ready);

            var workers = clone.livenessRegistry().workers();
            assertEquals(2, workers.length);
            assertTrue(workers[0].running());
            assertTrue(workers[1].running());
            assertNotNull(workers[0].thread());
            assertNotNull(workers[1].thread());

            fragment.close();

            assertTrue(clone.livenessRegistry().awaitTermination(System.nanoTime() + TIMEOUT.toNanos()));
            workers = clone.livenessRegistry().workers();
            assertFalse(workers[0].running());
            assertFalse(workers[1].running());
            assertNull(workers[0].thread());
            assertNull(workers[1].thread());
        } finally {
            fragment.close();
            awaitWorkersAndCloseExecutors(fragment);
        }
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
                assertTrue(clone.livenessRegistry().awaitTermination(System.nanoTime() + TIMEOUT.toNanos()));
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
            awaitWorkersAndCloseExecutors(fragment);
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
            awaitWorkersAndCloseExecutors(fragment);
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
            awaitWorkersAndCloseExecutors(fragment);
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
            awaitWorkersAndCloseExecutors(fragment);
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
            awaitWorkersAndCloseExecutors(fragment);
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
            awaitWorkersAndCloseExecutors(fragment);
        }
    }

    /// With the first-ranked worker held inside a frame, a worker that sits out is the only one that can
    /// enter the handles; it must not sit out for good.
    @Test
    void aWorkerThatSitsOutStillEntersAHandle() throws Exception {
        long entries = withWorkerSittingOut(quickIdle(), worker -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (worker.entries() == 0L && System.nanoTime() < deadline) {
                worker.cycle();
            }
            return worker.entries();
        });
        assertTrue(entries > 0L, "an idle worker never entered the handle while the first-ranked worker was held");
    }

    /// The participation model is intact: within the probe interval the worker still sits out.
    @Test
    void aWorkerSitsOutBetweenProbes() throws Exception {
        long entries = withWorkerSittingOut(quickIdle().withProbeIntervalNs(TimeUnit.HOURS.toNanos(1)), worker -> {
            for (int i = 0; i < 20_000; i++) {
                worker.cycle();
            }
            return worker.entries();
        });
        assertEquals(0L, entries, "the worker sat out every cycle");
    }

    @Test
    void aZeroProbeIntervalNeverSitsOut() throws Exception {
        long entries = withWorkerSittingOut(quickIdle().withProbeIntervalNs(0L), worker -> {
            for (int i = 0; i < 100; i++) {
                worker.cycle();
            }
            return worker.entries();
        });
        assertTrue(entries >= 100L, "every cycle entered a handle: " + entries);
    }

    /// A probe is one `DIRECT` cycle (a pull of the handle, then a request of it when the pull ran nothing),
    /// and the next comes a probe interval later.
    @Test
    void aWorkerThatSitsOutProbesOncePerInterval() throws Exception {
        long interval = TimeUnit.MILLISECONDS.toNanos(50);
        long entriesPerProbe = 2L;
        long[] result = withWorkerSittingOut(quickIdle().withProbeIntervalNs(interval), worker -> {
            long start = System.nanoTime();
            while (System.nanoTime() - start < 4 * interval) {
                worker.cycle();
            }
            return new long[] {worker.entries(), System.nanoTime() - start};
        });
        long entries = result[0];
        long most = (result[1] / interval + 1) * entriesPerProbe;
        assertTrue(entries >= entriesPerProbe, "a probe was made: " + entries);
        assertTrue(entries <= most, "at most one probe per interval: " + entries + " in " + result[1] + " ns");
    }

    /// A lone worker over `n` handles with nothing to give reaches the unproductive park within a bounded number of
    /// cycles, however many handles there are. A `DIRECT` cycle that finds nothing is one pull and one request, and
    /// each services one handle when the demand is a single bucket: if the request took the handle after the one
    /// the pull took, an even number of handles would split in two, and the half that is only requested never reads
    /// as empty and keeps its worker from parking.
    @Test
    void aWorkerOverIdleHandlesReachesTheUnproductiveParkWhateverTheirNumber() throws Exception {
        assumeTrue(!workerCpus().isEmpty(), "Requires a CPU the worker thread can be pinned to");
        IdlePolicy idle = new IdlePolicy(0L, IdlePolicy.DEFAULT_CONTENTION_HALF_LIFE_NANOS).withUnproductiveParkNs(1L);
        try {
            for (int n = 1; n <= 8; n++) {
                int count = n;
                TestDistributor.resetSharedRoutingState();
                ControlPlaneFragment worker = new ControlPlaneFragment(configWith(idle, 0));
                try {
                    long[] result = onPinnedThread(worker, () -> {
                        FlowThread.clearContext();
                        UpstreamQueue.UP_QUEUE.remove();
                        try {
                            FlowThread.FlowContext context = FlowThread.initializeContext();
                            worker.register();
                            UpstreamQueue queue = worker.getThreadUpstreamQueue();
                            context.upstream = queue;
                            CountingHandle[] handles = new CountingHandle[count];
                            for (int i = 0; i < count; i++) {
                                handles[i] = new CountingHandle();
                                worker.addUpstream(handles[i]);
                            }
                            FragmentDecisionTree policy = new FragmentDecisionTree(idle, 1_000L, 1_000L);
                            for (int i = 0; i < FragmentDecisionTree.BODY_COST_MIN_HISTORY; i++) {
                                policy.recordBodyCost(100L);
                            }
                            setField(worker, "controlPolicy", policy);
                            setField(worker, "upstreamQueue", queue);
                            Object state = getField(worker, "state");
                            java.lang.reflect.Field registered =
                                    state.getClass().getDeclaredField("registeredWorkers");
                            registered.setAccessible(true);
                            registered.setInt(state, 1);
                            Method runCycle = ControlPlaneFragment.class.getDeclaredMethod(
                                    "runCycle", FlowThread.FlowContext.class);
                            runCycle.setAccessible(true);

                            // One cycle per handle services each handle once; a second round makes sure.
                            int bound = 2 * count;
                            long parkedAt = -1L;
                            for (int cycle = 1; cycle <= bound && parkedAt < 0L; cycle++) {
                                runCycle.invoke(worker, context);
                                if (queue.getProductiveHandleCount() == 0L) {
                                    parkedAt = cycle;
                                }
                            }
                            long minPulls = Long.MAX_VALUE;
                            long minRequests = Long.MAX_VALUE;
                            for (CountingHandle handle : handles) {
                                minPulls = Math.min(minPulls, handle.pulls);
                                minRequests = Math.min(minRequests, handle.requests);
                            }
                            return new long[] {parkedAt, minPulls, minRequests};
                        } finally {
                            worker.removeThread();
                            FlowThread.clearContext();
                            UpstreamQueue.UP_QUEUE.remove();
                        }
                    });
                    assertTrue(
                            result[0] > 0L, count + " idle handles: still productive after " + 2 * count + " cycles");
                    assertTrue(result[1] > 0L, count + " idle handles: a handle was never pulled");
                    assertTrue(result[2] > 0L, count + " idle handles: a handle was never requested");
                } finally {
                    worker.close();
                    TestDistributor.resetSharedRoutingState();
                }
            }
        } finally {
            PinnedThreadExecutor.closeAll();
        }
    }

    private static void setField(ControlPlaneFragment fragment, String name, Object value)
            throws ReflectiveOperationException {
        Field field = ControlPlaneFragment.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(fragment, value);
    }

    private static Object getField(ControlPlaneFragment fragment, String name) throws ReflectiveOperationException {
        Field field = ControlPlaneFragment.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(fragment);
    }

    /// An upstream handle with nothing to give, which counts its pulls and its requests apart.
    private static final class CountingHandle extends UpstreamQueue.UpstreamHandle {

        long pulls;
        long requests;
        boolean productive = true;

        @Override
        public long getId() {
            return 1L;
        }

        @Override
        public long pull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            this.pulls++;
            this.productive = false;
            return 0L;
        }

        @Override
        public void request(long demand) {
            this.requests++;
        }

        @Override
        public boolean isComplete() {
            return false;
        }

        @Override
        public boolean isProductive() {
            return this.productive;
        }

        @Override
        public void setProductivity(boolean productive) {
            this.productive = productive;
        }

        @Override
        public void push(AbstractFrame frame) {}

        @Override
        public void onError(Throwable throwable) {}
    }

    /// A fixed, quick idle timing whose workers never take the unproductive park, so hand-driven cycles are fast.
    private static IdlePolicy quickIdle() {
        return new IdlePolicy(0L, IdlePolicy.DEFAULT_CONTENTION_HALF_LIFE_NANOS).withUnproductiveParkNs(0L);
    }

    private interface Sitting<T> {

        T run(SittingWorker worker) throws Exception;
    }

    /// Registers a first-ranked worker that is never cycled (it is "inside a frame"), then runs `scenario`
    /// on the pinned thread of a rank-2 worker whose policy says `IDLE`: it has a body-cost history and its
    /// only handle looked empty. The scenario drives that worker's cycles by hand.
    private static <T> T withWorkerSittingOut(IdlePolicy idle, Sitting<T> scenario) throws Exception {
        requireTwoWorkerCores();
        TestDistributor.resetSharedRoutingState();
        ControlPlaneFragment anchor = new ControlPlaneFragment(configWith(idle, 0));
        ControlPlaneFragment worker = new ControlPlaneFragment(configWith(idle, 1));
        try {
            onPinnedThread(anchor, () -> {
                anchor.register();
                return null;
            });
            return onPinnedThread(worker, () -> {
                FlowThread.clearContext();
                UpstreamQueue.UP_QUEUE.remove();
                try {
                    return scenario.run(new SittingWorker(anchor, worker, idle));
                } finally {
                    worker.removeThread();
                    FlowThread.clearContext();
                    UpstreamQueue.UP_QUEUE.remove();
                }
            });
        } finally {
            anchor.close();
            worker.close();
            TestDistributor.resetSharedRoutingState();
            PinnedThreadExecutor.closeAll();
        }
    }

    private static FragmentConfig configWith(IdlePolicy idle, int coreIndex) {
        FragmentConfig base = FragmentConfig.ofDefaults().clone(cloneConfigOnCoreIndex(coreIndex));
        return new FragmentConfig(
                base.cloneConfig(),
                base.cacheConfig(),
                base.observer(),
                base.maxBatchSize(),
                base.smtEnabled(),
                idle,
                base.benchmarkMode(),
                base.metricPrefix(),
                base.registry());
    }

    private static <T> T onPinnedThread(ControlPlaneFragment fragment, Callable<T> task) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        PinnedThreadExecutor.get(fragment.cpu).execute(() -> {
            try {
                done.complete(task.call());
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        });
        try {
            return done.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) {
                throw cause;
            }
            if (failure.getCause() instanceof Error cause) {
                throw cause;
            }
            throw failure;
        }
    }

    /// An upstream handle with nothing to give, which counts how often a worker enters it.
    private static final class EnteredHandle extends UpstreamQueue.UpstreamHandle {

        long entries;
        boolean productive = true;

        @Override
        public long getId() {
            return 1L;
        }

        @Override
        public boolean acquireLock() {
            this.entries++;
            return true;
        }

        @Override
        public long pull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            this.productive = false;
            return 0L;
        }

        @Override
        public void request(long demand) {}

        @Override
        public boolean isComplete() {
            return false;
        }

        @Override
        public boolean isProductive() {
            return this.productive;
        }

        @Override
        public void setProductivity(boolean productive) {
            this.productive = productive;
        }

        @Override
        public void push(AbstractFrame frame) {}

        @Override
        public void onError(Throwable throwable) {}
    }

    /// A rank-2 worker, cycled by the thread that creates it (its pinned thread) through the worker loop's own
    /// cycle, with its preconditions asserted: rank 2 behind a registered first-ranked worker, a body-cost
    /// history, no productive handle, and a policy that says `IDLE`.
    private static final class SittingWorker {

        private final ControlPlaneFragment worker;
        private final FlowThread.FlowContext context;
        private final EnteredHandle handle = new EnteredHandle();
        private final Method runCycle;

        private SittingWorker(ControlPlaneFragment anchor, ControlPlaneFragment worker, IdlePolicy idle)
                throws ReflectiveOperationException {
            this.worker = worker;
            this.context = FlowThread.initializeContext();
            worker.register();
            UpstreamQueue queue = worker.getThreadUpstreamQueue();
            this.context.upstream = queue;
            assertEquals(worker.cpu, queue.cpu, "the cycles run on the worker's own cpu");
            assertEquals(1, TestDistributor.rankOf(anchor.cpu));
            assertEquals(2, TestDistributor.rankOf(worker.cpu));
            worker.addUpstream(this.handle);

            FragmentDecisionTree policy = new FragmentDecisionTree(idle, 1_000L, 1_000L);
            for (int i = 0; i < FragmentDecisionTree.BODY_COST_MIN_HISTORY; i++) {
                policy.recordBodyCost(100L);
            }
            install("controlPolicy", policy);
            install("upstreamQueue", queue);
            Field stateField = ControlPlaneFragment.class.getDeclaredField("state");
            stateField.setAccessible(true);
            Object state = stateField.get(worker);
            Field registered = state.getClass().getDeclaredField("registeredWorkers");
            registered.setAccessible(true);
            registered.setInt(state, 2);

            // One look at the handle shows it empty: the worker's view of it is unproductive.
            queue.pull(frame -> {}, frame -> false, 1L);
            assertEquals(0L, queue.getProductiveHandleCount());
            assertTrue(policy.shouldIdle(0L, 0L, 2, 2));
            this.handle.entries = 0L;

            this.runCycle = ControlPlaneFragment.class.getDeclaredMethod("runCycle", FlowThread.FlowContext.class);
            this.runCycle.setAccessible(true);
        }

        private void install(String field, Object value) throws ReflectiveOperationException {
            Field f = ControlPlaneFragment.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(this.worker, value);
        }

        void cycle() throws ReflectiveOperationException {
            try {
                this.runCycle.invoke(this.worker, this.context);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw failure;
            }
        }

        long entries() {
            return this.handle.entries;
        }
    }

    private static void awaitWorkersAndCloseExecutors(ControlPlaneFragment fragment) {
        CloneConfig clone = fragment.getConfig().cloneConfig();
        if (clone != null) {
            assertTrue(clone.livenessRegistry().awaitTermination(System.nanoTime() + TIMEOUT.toNanos()));
        }
        PinnedThreadExecutor.closeAll();
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

        private static int rankOf(int cpu) {
            return CPU_RANK.getAcquire().get(cpu);
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

    private static final class InterruptIgnoringReceiver implements LatticeReceiver {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void push(AbstractFrame frame) {
            this.entered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    if (!this.release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("terminal release timed out");
                    }
                    break;
                } catch (InterruptedException expected) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void onComplete() {}

        @Override
        public void onError(Throwable throwable) {}

        @Override
        public void addUpstream(LatticeSource upstream) {}
    }
}
