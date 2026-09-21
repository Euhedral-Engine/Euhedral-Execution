package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.config.IdlePolicy;
import io.euhedral_execution.core.flow_control.LatticeHotSource;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.frames.DummyFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.common.SystemUtilization;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.Mockito;

@Isolated
class ControlPlaneFragmentTest {

    private final List<ControlPlaneFragment> fragments = new ArrayList<>();
    private final List<PinnedThreadExecutor> executors = new ArrayList<>();
    private ControlPlaneCache[] workStealRegistry;
    private ControlPlaneCache[] savedWorkStealRegistry;

    @BeforeEach
    void saveWorkStealRegistry() throws ReflectiveOperationException {
        var field = ControlPlaneCache.class.getDeclaredField("WORK_STEAL");
        field.setAccessible(true);
        this.workStealRegistry = (ControlPlaneCache[]) field.get(null);
        this.savedWorkStealRegistry = this.workStealRegistry.clone();
    }

    private static FragmentConfig workerConfig() {
        return FragmentConfig.ofDefaults().clone(cloneConfig());
    }

    private static CloneConfig cloneConfig() {
        int cpu = SystemInfo.getCpuSet().nextSetBit(0);
        if (cpu < 0) {
            throw new IllegalStateException("No CPU is available for the unit test");
        }
        CpuInfo info = SystemInfo.getCpuInfo(cpu);
        BitSet cpus = new BitSet();
        cpus.set(cpu);
        return new CloneConfig("test", info.core(), cpus);
    }

    @AfterEach
    void closeFragments() {
        List<Executable> cleanup = new ArrayList<>();
        for (ControlPlaneFragment fragment : this.fragments) {
            cleanup.add(fragment::close);
        }
        cleanup.add(PinnedThreadExecutor::closeAll);
        cleanup.add(() -> {
            // close() can time out or return interrupted; only task termination permits restoration.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (PinnedThreadExecutor executor : this.executors) {
                assertTrue(
                        executor.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS),
                        "Fixture pinned tasks did not terminate on CPU " + executor.getCpu());
            }
            // Restore the saved registry after verifying every pinned task terminated.
            if (this.savedWorkStealRegistry != null) {
                System.arraycopy(
                        this.savedWorkStealRegistry, 0, this.workStealRegistry, 0, this.workStealRegistry.length);
            }
        });
        assertAll("Fixture cleanup", cleanup);
    }

    @Test
    void baseConfigurationCannotStartWithoutCpuOwnership() {
        try (ControlPlaneFragment fragment = create(FragmentConfig.ofDefaults())) {

            assertFalse(fragment.isStarted());
            assertEquals(-1, fragment.core);
            assertNull(fragment.output());
            assertThrows(IllegalStateException.class, fragment::start);
        }
    }

    @Test
    void clonedConfigurationCreatesWorkerInfrastructure() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            assertNotNull(fragment.output());
            assertNotNull(fragment.getLocalCache());
            assertNotNull(fragment.outputStream);
        }
    }

    @Test
    void cleanupRestoresWorkStealRegistrations() throws ReflectiveOperationException {
        var field = ControlPlaneCache.class.getDeclaredField("WORK_STEAL");
        field.setAccessible(true);
        ControlPlaneCache[] registry = (ControlPlaneCache[]) field.get(null);
        ControlPlaneCache[] before = registry.clone();
        create(workerConfig());
        assertFalse(Arrays.equals(before, registry), "The fixture must actually replace a registration");

        closeFragments();

        assertArrayEquals(before, registry, "Fixture cleanup must not expose retired caches to another test");
    }

    @Test
    void cleanupWithdrawsRegistrationWhileWorkerIsActive() throws InterruptedException {
        assertCleanupWithdrawsActiveRegistration(false, false);
    }

    @Test
    void interruptedCleanupWithdrawsRegistrationWhileWorkerIsActive() throws InterruptedException {
        assertCleanupWithdrawsActiveRegistration(true, false);
    }

    @Test
    void failedCleanupWithdrawsRegistrationWhileWorkerIsActive() throws InterruptedException {
        assertCleanupWithdrawsActiveRegistration(false, true);
    }

    private void assertCleanupWithdrawsActiveRegistration(boolean interrupted, boolean cleanupFails)
            throws InterruptedException {
        ControlPlaneFragment fragment = create(workerConfig());
        PinnedThreadExecutor executor = PinnedThreadExecutor.get(fragment.cpu);
        ControlPlaneFragment failedClose = Mockito.mock(ControlPlaneFragment.class);
        if (cleanupFails) {
            Mockito.doThrow(new IllegalStateException("fixture close failure"))
                    .when(failedClose)
                    .close();
            this.fragments.addFirst(failedClose);
        }
        var entered = new CountDownLatch(1);
        var release = new CompletableFuture<Void>();
        var receiver = Mockito.mock(LatticeReceiver.class);
        Mockito.doAnswer(call -> {
                    entered.countDown();
                    release.join(); // Deliberately ignore shutdown interruption until the test releases ownership.
                    return null;
                })
                .when(receiver)
                .push(Mockito.any());
        fragment.output().addDownstream(receiver);
        fragment.push(DummyFrame.INSTANCE);
        try {
            fragment.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            ControlPlaneCache[] activeRegistry = this.workStealRegistry.clone();
            assertFalse(Arrays.equals(this.savedWorkStealRegistry, activeRegistry));

            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            AssertionError failure = assertThrows(AssertionError.class, this::closeFragments);
            assertTrue(failure.getMessage().contains("Fixture pinned tasks did not terminate"));
            if (cleanupFails) {
                assertTrue(failure.getMessage().contains("fixture close failure"));
            }

            assertEquals(interrupted, Thread.currentThread().isInterrupted(), "Preserve caller interruption");
            assertFalse(executor.isTerminated(), "Shutdown is not task termination");
            assertNotSame(
                    fragment,
                    this.workStealRegistry[fragment.cpu],
                    "Retirement withdraws the exact cache even while its worker finishes");
        } finally {
            this.fragments.remove(failedClose);
            Thread.interrupted();
            release.complete(null);
            executor.close();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Regression worker must terminate");
        }
        closeFragments();
        assertArrayEquals(this.savedWorkStealRegistry, this.workStealRegistry);
    }

    @Test
    void cleanupClosesRemainingFragmentsWhenOneCloseFails() {
        SimpleMeterRegistry registry = Mockito.spy(new SimpleMeterRegistry());
        FragmentConfig base = workerConfig();
        FragmentConfig config = new FragmentConfig(
                base.cloneConfig(),
                base.cacheConfig(),
                base.observer(),
                base.maxBatchSize(),
                base.smtEnabled(),
                base.idlePolicy(),
                base.benchmarkMode(),
                base.metricPrefix(),
                registry);
        create(config);
        ControlPlaneFragment later = create(workerConfig());
        assertFalse(Arrays.equals(this.savedWorkStealRegistry, this.workStealRegistry));
        Mockito.doThrow(new IllegalStateException("fixture metrics close failure"))
                .when(registry)
                .remove(Mockito.any(Meter.class));
        try {
            AssertionError failure = assertThrows(AssertionError.class, this::closeFragments);

            assertTrue(failure.getMessage().contains("fixture metrics close failure"));
            assertTrue(later.isClosed(), "A failed close must not skip remaining fixture cleanup");
            assertTrue(this.executors.stream().allMatch(PinnedThreadExecutor::isTerminated));
            assertArrayEquals(this.savedWorkStealRegistry, this.workStealRegistry);
        } finally {
            Mockito.doCallRealMethod().when(registry).remove(Mockito.any(Meter.class));
        }
    }

    @Test
    void smtRoutingSendsTheSecondLogicalLaneToTheBuddyCache() {
        BitSet available = SystemInfo.getCpuSet();
        int primaryCpu = available.nextSetBit(0);
        int buddyCpu = available.nextSetBit(primaryCpu + 1);
        assumeTrue(buddyCpu >= 0, "Requires two available logical CPUs");

        BitSet cpus = new BitSet();
        cpus.set(primaryCpu);
        cpus.set(buddyCpu);
        CloneConfig clone = new CloneConfig(
                "smt-routing-test", SystemInfo.getCpuInfo(primaryCpu).core(), cpus);
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

        try (ControlPlaneFragment fragment = create(config)) {
            AbstractFrame frame = Mockito.mock(AbstractFrame.class);
            Mockito.when(frame.getRoutingHash()).thenReturn(1L);

            fragment.push(frame);

            assertEquals(0L, fragment.getLocalCacheCount());
            assertEquals(1L, fragment.getSmtBuddy().getLocalCacheCount());
        }
    }

    @Test
    void drainModeIsPropagatedToTheWorkerCache() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            fragment.setDrainMode(true);

            assertTrue(fragment.getDrainFlag().getAcquire());
        }
    }

    @Test
    void firstTouchInitializesWorkerOwnedStructures() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            assertDoesNotThrow(fragment::firstTouch);
        }
    }

    @Test
    void cloneCreatesAnIndependentWorkerWithTheRequestedOwnership() {
        ControlPlaneFragment fragment = create(workerConfig());
        CloneConfig cloneConfig = cloneConfig();

        ControlPlaneFragment cloned = fragment.clone(cloneConfig);
        track(cloned);

        assertNotSame(fragment, cloned);
        assertSame(cloneConfig, cloned.getConfig().cloneConfig());
    }

    @Test
    void fragmentConfigurationPropagatesCloneOwnership() {
        CloneConfig cloneConfig = cloneConfig();

        FragmentConfig cloned = FragmentConfig.ofDefaults().clone(cloneConfig);

        assertSame(cloneConfig, cloned.cloneConfig());
        assertSame(cloneConfig, cloned.cacheConfig().cloneConfig());
    }

    @Test
    void stoppedWorkerIsDrainedAndCanResetSynchronously() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {

            assertTrue(fragment.isDrained());
            assertEquals(0, fragment.reset(System.nanoTime()));
        }
    }

    @Test
    void cachedWorkDrainsBeforeWithdrawalWithScarcityGateEnabled() throws Exception {
        var base = workerConfig();
        var config = new FragmentConfig(
                base.cloneConfig(),
                base.cacheConfig(),
                base.observer(),
                base.maxBatchSize(),
                base.smtEnabled(),
                new IdlePolicy(15000, 1000000, null),
                base.benchmarkMode(),
                base.metricPrefix(),
                base.registry());
        var fragment = create(config);
        for (int i = 0; i < 7; i++) {
            fragment.ingest(new LatticeHotSource());
        }
        var received = new CountDownLatch(1);
        var receiver = Mockito.mock(LatticeReceiver.class);
        Mockito.doAnswer(call -> {
                    received.countDown();
                    return null;
                })
                .when(receiver)
                .push(Mockito.any());
        fragment.output().addDownstream(receiver);
        fragment.push(DummyFrame.INSTANCE);
        assertEquals(1, fragment.getLocalCacheCount());
        fragment.start();
        assertTrue(received.await(5, TimeUnit.SECONDS));
        // The exact frame already in the local cache reached the output on the owner thread.
        Mockito.verify(receiver).push(DummyFrame.INSTANCE);
    }

    @Test
    void closeIsIdempotentBeforeStart() {
        ControlPlaneFragment fragment = create(workerConfig());

        fragment.close();

        assertDoesNotThrow(fragment::close);
    }

    @Test
    void closeCompletesLatticeCleanupWhenMetricsCleanupFails() {
        SimpleMeterRegistry registry = Mockito.spy(new SimpleMeterRegistry());
        FragmentConfig base = workerConfig();
        FragmentConfig config = new FragmentConfig(
                base.cloneConfig(),
                base.cacheConfig(),
                base.observer(),
                base.maxBatchSize(),
                base.smtEnabled(),
                base.idlePolicy(),
                base.benchmarkMode(),
                base.metricPrefix(),
                registry);
        ControlPlaneFragment fragment = create(config);
        Mockito.doThrow(new IllegalStateException("metrics close failure"))
                .when(registry)
                .remove(Mockito.any(Meter.class));

        assertThrows(IllegalStateException.class, fragment::close);
        assertTrue(fragment.isClosed());

        Mockito.doCallRealMethod().when(registry).remove(Mockito.any(Meter.class));
        assertDoesNotThrow(fragment::close);
        assertTrue(registry.getMeters().isEmpty());
    }

    @Test
    void closedFragmentCannotBeStarted() {
        ControlPlaneFragment fragment = create(workerConfig());
        fragment.close();

        assertThrows(IllegalStateException.class, fragment::start);
        assertFalse(fragment.isStarted());
    }

    @Test
    void shouldUpdateAdaptiveBatchCapFromValidSnapshot() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            int cpu = fragment.cpu;

            SystemUtilization.CpuSnapshot cpuSnap = Mockito.mock(SystemUtilization.CpuSnapshot.class);
            Mockito.when(cpuSnap.pressure()).thenReturn(0.50);
            Mockito.when(cpuSnap.lastUsageNs()).thenReturn(100L);

            SystemUtilization.CpuSnapshot[] cpus = new SystemUtilization.CpuSnapshot[cpu + 1];
            cpus[cpu] = cpuSnap;

            SystemUtilization.CoreSnapshot snapshot = Mockito.mock(SystemUtilization.CoreSnapshot.class);
            org.mockito.Mockito.when(snapshot.cpuSnapshots()).thenReturn(cpus);

            fragment.update(snapshot);

            assertTrue(fragment.getAdaptiveBatchCap() >= 2L);
        }
    }

    @Test
    void shouldEnforceMinimumBatchSizeFloorOfTwo() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            int cpu = fragment.cpu;

            SystemUtilization.CpuSnapshot cpuSnap = Mockito.mock(SystemUtilization.CpuSnapshot.class);
            Mockito.when(cpuSnap.pressure()).thenReturn(1.00);
            Mockito.when(cpuSnap.lastUsageNs()).thenReturn(200L);

            SystemUtilization.CpuSnapshot[] cpus = new SystemUtilization.CpuSnapshot[cpu + 1];
            cpus[cpu] = cpuSnap;

            SystemUtilization.CoreSnapshot snapshot = Mockito.mock(SystemUtilization.CoreSnapshot.class);
            Mockito.when(snapshot.cpuSnapshots()).thenReturn(cpus);

            fragment.update(snapshot);

            assertEquals(2L, fragment.getAdaptiveBatchCap());
        }
    }

    @Test
    void stoppedResetRestoresTheInitialAdaptiveBatchCap() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            int cpu = fragment.cpu;
            SystemUtilization.CpuSnapshot cpuSnap = Mockito.mock(SystemUtilization.CpuSnapshot.class);
            Mockito.when(cpuSnap.pressure()).thenReturn(1.0);
            Mockito.when(cpuSnap.lastUsageNs()).thenReturn(250L);
            SystemUtilization.CpuSnapshot[] cpus = new SystemUtilization.CpuSnapshot[cpu + 1];
            cpus[cpu] = cpuSnap;
            SystemUtilization.CoreSnapshot snapshot = Mockito.mock(SystemUtilization.CoreSnapshot.class);
            Mockito.when(snapshot.cpuSnapshots()).thenReturn(cpus);

            fragment.update(snapshot);
            assertEquals(2L, fragment.getAdaptiveBatchCap());

            fragment.reset(System.nanoTime());

            long initialCap = Math.max(2L, Math.min(fragment.getConfig().maxBatchSize(), fragment.getFrameQuota()));
            assertEquals(initialCap, fragment.getAdaptiveBatchCap());
        }
    }

    @Test
    void shouldRejectMalformedOrOutOfOrderSnapshots() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            int cpu = fragment.cpu;

            SystemUtilization.CpuSnapshot cpuSnap1 = Mockito.mock(SystemUtilization.CpuSnapshot.class);
            Mockito.when(cpuSnap1.pressure()).thenReturn(0.0);
            Mockito.when(cpuSnap1.lastUsageNs()).thenReturn(500L);

            SystemUtilization.CpuSnapshot[] cpus1 = new SystemUtilization.CpuSnapshot[cpu + 1];
            cpus1[cpu] = cpuSnap1;

            SystemUtilization.CoreSnapshot snapshot1 = Mockito.mock(SystemUtilization.CoreSnapshot.class);
            Mockito.when(snapshot1.cpuSnapshots()).thenReturn(cpus1);

            fragment.update(snapshot1);
            long cap1 = fragment.getAdaptiveBatchCap();

            SystemUtilization.CpuSnapshot cpuSnap2 = Mockito.mock(SystemUtilization.CpuSnapshot.class);
            Mockito.when(cpuSnap2.pressure()).thenReturn(1.0);
            Mockito.when(cpuSnap2.lastUsageNs()).thenReturn(300L);

            SystemUtilization.CpuSnapshot[] cpus2 = new SystemUtilization.CpuSnapshot[cpu + 1];
            cpus2[cpu] = cpuSnap2;

            SystemUtilization.CoreSnapshot snapshot2 = Mockito.mock(SystemUtilization.CoreSnapshot.class);
            Mockito.when(snapshot2.cpuSnapshots()).thenReturn(cpus2);

            fragment.update(snapshot2);

            assertEquals(cap1, fragment.getAdaptiveBatchCap());
        }
    }

    @Test
    void shouldIgnoreNullAndSparseSnapshots() {
        try (ControlPlaneFragment fragment = create(workerConfig())) {
            long capBefore = fragment.getAdaptiveBatchCap();

            assertDoesNotThrow(() -> fragment.update(null));
            assertEquals(capBefore, fragment.getAdaptiveBatchCap());

            SystemUtilization.CoreSnapshot mockSnap = Mockito.mock(SystemUtilization.CoreSnapshot.class);
            Mockito.when(mockSnap.cpuSnapshots()).thenReturn(new SystemUtilization.CpuSnapshot[0]);
            assertDoesNotThrow(() -> fragment.update(mockSnap));
            assertEquals(capBefore, fragment.getAdaptiveBatchCap());
        }
    }

    private ControlPlaneFragment create(FragmentConfig config) {
        ControlPlaneFragment fragment = new ControlPlaneFragment(config);
        track(fragment);
        return fragment;
    }

    private void track(ControlPlaneFragment fragment) {
        this.fragments.add(fragment);
        // Retain the exact primary/buddy identities before close makes registry lookups unavailable.
        for (ControlPlaneFragment worker = fragment; worker != null; worker = worker.getSmtBuddy()) {
            if (worker.cpu >= 0) {
                PinnedThreadExecutor executor = PinnedThreadExecutor.get(worker.cpu);
                assertNotNull(executor);
                if (!this.executors.contains(executor)) {
                    this.executors.add(executor);
                }
            }
        }
    }
}
