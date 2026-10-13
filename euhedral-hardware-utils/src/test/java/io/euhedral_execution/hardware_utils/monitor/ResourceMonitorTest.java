package io.euhedral_execution.hardware_utils.monitor;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.monitor.internal.DeadlineWaiter;
import io.euhedral_execution.hardware_utils.monitor.internal.MonotonicClock;
import io.euhedral_execution.hardware_utils.monitor.internal.TopologyUpdater;
import io.euhedral_execution.hardware_utils.topology.SystemInfo;
import io.euhedral_execution.hardware_utils.topology.TopologyMapper;
import io.euhedral_execution.hardware_utils.util.UnmodifiableBitSet;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.BitSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated
class ResourceMonitorTest {

    @Test
    void samplesUtilizationBeforeStartAndCachesIt() {
        IncrementingSnapshotProvider snapshots = new IncrementingSnapshotProvider();
        MonotonicClock clock = () -> snapshots.startTime + SECONDS.toNanos(snapshots.samples.get());
        AtomicInteger topologyUpdates = new AtomicInteger();

        try (ResourceMonitor monitor = new ResourceMonitor(
                utilization -> topologyUpdates.incrementAndGet(),
                Duration.ofMillis(200),
                snapshots,
                clock,
                (deadline, clk) -> {},
                Thread::new)) {
            HardwareUtilization first = monitor.getUtilization();
            int samplesAfterFirstRead = snapshots.samples.get();

            assertSame(first, monitor.getUtilization());
            assertEquals(samplesAfterFirstRead, snapshots.samples.get());
            assertEquals(1, topologyUpdates.get());
        }
    }

    @Test
    void calculatesUtilizationFromInjectedSnapshots() throws InterruptedException {
        IncrementingSnapshotProvider snapshots = new IncrementingSnapshotProvider();
        // Clock returns observed time of the most recently-fetched snapshot so that
        // each sampleFast() call produces an interval exactly 1 second wide.
        MonotonicClock clock = () -> snapshots.startTime + SECONDS.toNanos(snapshots.samples.get() - 1);
        // Waiter that returns immediately so the polling loop runs without sleeping.
        DeadlineWaiter noWait = (deadline, clk) -> {};

        HardwareUtilization[] captured = {null};
        int[] publishCount = {0};

        try (ResourceMonitor monitor = new ResourceMonitor(
                TopologyUpdater.from(new TopologyMapper(new BitSet())),
                Duration.ofMillis(200),
                snapshots,
                clock,
                noWait,
                Thread::new)) {
            monitor.addListener(u -> {
                captured[0] = u;
                publishCount[0]++;
            });
            monitor.start();

            // Wait until at least 4 publications so EWMA converges (waiter is immediate, fast).
            long deadline = System.nanoTime() + SECONDS.toNanos(5);
            while (publishCount[0] < 4 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        }

        HardwareUtilization utilization = captured[0];
        assertTrue(utilization != null, "monitor never published utilization");
        assertEquals(1.0, utilization.quotaCpus());
        assertTrue(utilization.quotaCpuUsage() > 0.45, "quotaCpuUsage too low: " + utilization.quotaCpuUsage());
        assertTrue(utilization.quotaCpuUsage() < 0.55, "quotaCpuUsage too high: " + utilization.quotaCpuUsage());
        assertEquals(0.1, utilization.cpuThrottleRatio(), 0.01);
        assertEquals(1_000, utilization.globalMemoryPool());
        assertEquals(0.5, utilization.totalMemoryUtilization());
        assertTrue(utilization.diskIOBytesPerSecond() > 0.0);
        assertEquals(snapshots.effectiveCpus, utilization.globalEffectiveCpus());
    }

    private static final DeadlineWaiter NO_WAIT = (deadline, clk) -> {};

    private static ResourceMonitor monitor(IncrementingSnapshotProvider snapshots, ThreadFactory factory) {
        MonotonicClock clock = () -> snapshots.startTime + SECONDS.toNanos(snapshots.samples.get());
        return new ResourceMonitor(
                TopologyUpdater.from(new TopologyMapper(new BitSet())),
                Duration.ofMillis(200),
                snapshots,
                clock,
                NO_WAIT,
                factory);
    }

    @Test
    void stoppedMonitorDoesNotSpin() throws InterruptedException {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(bean.isThreadCpuTimeSupported());
        AtomicReference<Thread> polling = new AtomicReference<>();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            polling.set(t);
            return t;
        };
        try (ResourceMonitor monitor = monitor(new IncrementingSnapshotProvider(), factory)) {
            monitor.start();
            monitor.stop();
            Thread t = polling.get();
            // Let the thread settle into its STOPPED wait (it must park, not spin).
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (t.getState() != Thread.State.WAITING
                        && !Thread.currentThread().isInterrupted()) {
                    Thread.yield();
                }
            });
            long before = bean.getThreadCpuTime(t.getId());
            Thread.sleep(200); // lets wall time pass only; no synchronization depends on it
            long after = bean.getThreadCpuTime(t.getId());
            assertTrue(before >= 0 && after >= 0, "polling thread terminated");
            assertTrue(after - before < 20_000_000L, "CPU ns burned while STOPPED: " + (after - before));
        }
    }

    @Test
    void interruptedStoppedPollerDoesNotSpin() throws InterruptedException {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(bean.isThreadCpuTimeSupported());
        AtomicReference<Thread> polling = new AtomicReference<>();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            polling.set(t);
            return t;
        };
        try (ResourceMonitor monitor = monitor(new IncrementingSnapshotProvider(), factory)) {
            monitor.start();
            monitor.stop();
            Thread t = polling.get();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (t.getState() != Thread.State.WAITING
                        && !Thread.currentThread().isInterrupted()) {
                    Thread.yield();
                }
            });
            // A foreign interrupt makes park return immediately; the poller must clear it and park again.
            t.interrupt();
            Thread.sleep(50); // let the poller process the interrupt before measuring
            long before = bean.getThreadCpuTime(t.getId());
            Thread.sleep(200); // lets wall time pass only; no synchronization depends on it
            long after = bean.getThreadCpuTime(t.getId());
            assertTrue(before >= 0 && after >= 0, "polling thread terminated");
            assertTrue(after - before < 20_000_000L, "CPU ns burned after interrupt: " + (after - before));
        }
    }

    @Test
    void closeFromListenerReturns() {
        ResourceMonitor monitor = monitor(new IncrementingSnapshotProvider(), Thread::new);
        CountDownLatch closed = new CountDownLatch(1);
        monitor.addListener(u -> {
            monitor.close();
            closed.countDown();
        });
        monitor.start();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertTrue(monitor.close(Duration.ofSeconds(5)), "monitor must finish closing asynchronously");
        });
    }

    @Test
    void closeWithBlockedProviderTimesOut() {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        IncrementingSnapshotProvider snapshots = new IncrementingSnapshotProvider() {
            @Override
            public SystemSnapshot getSnapshot() {
                // call 0 happens in the adapter constructor; later calls come from the polling thread
                if (calls.getAndIncrement() >= 1) {
                    entered.countDown();
                    // Uninterruptible on purpose: close() interrupts the polling thread.
                    boolean interrupted = false;
                    while (true) {
                        try {
                            release.await();
                            break;
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.getSnapshot();
            }
        };
        ResourceMonitor monitor = monitor(snapshots, Thread::new);
        try {
            monitor.start();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> entered.await());
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertFalse(monitor.close(Duration.ofMillis(100))));
        } finally {
            release.countDown();
        }
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(monitor.close(Duration.ofSeconds(4))));
    }

    @Test
    void providerExceptionDoesNotKillMonitor() {
        AtomicInteger calls = new AtomicInteger();
        IncrementingSnapshotProvider snapshots = new IncrementingSnapshotProvider() {
            @Override
            public SystemSnapshot getSnapshot() {
                // call 0 is the adapter constructor, call 1 is the first tick
                if (calls.getAndIncrement() == 1) {
                    throw new IllegalStateException("injected");
                }
                return super.getSnapshot();
            }
        };
        ResourceMonitor monitor = monitor(snapshots, Thread::new);
        CountDownLatch published = new CountDownLatch(1);
        monitor.addListener(u -> published.countDown());
        try {
            monitor.start();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(published.await(5, TimeUnit.SECONDS)));
            assertTrue(calls.get() > 2);
        } finally {
            monitor.close();
        }
    }

    @Test
    void threadFactoryFailureRollsBack() {
        AtomicInteger attempts = new AtomicInteger();
        ThreadFactory flaky = r -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("no threads");
            }
            return new Thread(r);
        };
        ResourceMonitor m = monitor(new IncrementingSnapshotProvider(), flaky);
        CountDownLatch published = new CountDownLatch(1);
        m.addListener(u -> published.countDown());
        try {
            assertThrows(IllegalStateException.class, m::start);
            m.start();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(published.await(5, TimeUnit.SECONDS)));
        } finally {
            m.close();
        }
    }

    @Test
    void nullThreadFromFactoryRollsBack() {
        AtomicInteger attempts = new AtomicInteger();
        ThreadFactory nullFirst = r -> attempts.getAndIncrement() == 0 ? null : new Thread(r);
        ResourceMonitor m = monitor(new IncrementingSnapshotProvider(), nullFirst);
        CountDownLatch published = new CountDownLatch(1);
        m.addListener(u -> published.countDown());
        try {
            assertThrows(IllegalStateException.class, m::start);
            m.start();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(published.await(5, TimeUnit.SECONDS)));
        } finally {
            m.close();
        }
    }

    @Test
    void stopStartStopCycleStaysResponsive() {
        ResourceMonitor monitor = monitor(new IncrementingSnapshotProvider(), Thread::new);
        CountDownLatch published = new CountDownLatch(1);
        monitor.addListener(u -> published.countDown());
        try {
            for (int i = 0; i < 50; i++) {
                monitor.start();
                monitor.stop();
            }
            monitor.start();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(published.await(5, TimeUnit.SECONDS)));
            monitor.stop();
        } finally {
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(monitor.close(Duration.ofSeconds(4))));
        }
    }

    @Test
    void closeRacingStartCompletes() {
        for (int i = 0; i < 50; i++) {
            ResourceMonitor monitor = monitor(new IncrementingSnapshotProvider(), Thread::new);
            Thread starter = new Thread(monitor::start);
            starter.start();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(monitor.close(Duration.ofSeconds(4))));
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> starter.join());
        }
    }

    @Test
    void rollbackAfterConcurrentStopStartLeavesMonitorStartable() {
        AtomicReference<ResourceMonitor> self = new AtomicReference<>();
        AtomicInteger attempts = new AtomicInteger();
        ThreadFactory factory = r -> {
            if (attempts.getAndIncrement() == 0) {
                // Drive the interleaving: STARTING -> STOPPED -> RUNNING while no thread exists.
                self.get().stop();
                self.get().start();
                throw new IllegalStateException("no threads");
            }
            return new Thread(r);
        };
        ResourceMonitor m = monitor(new IncrementingSnapshotProvider(), factory);
        self.set(m);
        CountDownLatch published = new CountDownLatch(1);
        m.addListener(u -> published.countDown());
        try {
            assertThrows(IllegalStateException.class, m::start);
            m.start();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(published.await(5, TimeUnit.SECONDS)));
        } finally {
            m.close();
        }
    }

    @Test
    void throwingClockDoesNotKillPoller() {
        IncrementingSnapshotProvider snapshots = new IncrementingSnapshotProvider();
        AtomicInteger clockCalls = new AtomicInteger();
        MonotonicClock clock = () -> {
            // call 1 is the loop's t0 and call 3 an in-loop read outside tick(); both throw
            int call = clockCalls.incrementAndGet();
            if (call == 1 || call == 3) {
                throw new IllegalStateException("clock");
            }
            return snapshots.startTime + SECONDS.toNanos(snapshots.samples.get());
        };
        ResourceMonitor m = new ResourceMonitor(
                TopologyUpdater.from(new TopologyMapper(new BitSet())),
                Duration.ofMillis(200),
                snapshots,
                clock,
                NO_WAIT,
                Thread::new);
        CountDownLatch published = new CountDownLatch(1);
        m.addListener(u -> published.countDown());
        try {
            m.start();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(published.await(5, TimeUnit.SECONDS)));
        } finally {
            m.close();
        }
    }

    @Test
    void throwingWaiterBacksOffAndPollerSurvives() throws InterruptedException {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(bean.isThreadCpuTimeSupported());
        IncrementingSnapshotProvider snapshots = new IncrementingSnapshotProvider();
        MonotonicClock clock = () -> snapshots.startTime + SECONDS.toNanos(snapshots.samples.get());
        AtomicInteger waits = new AtomicInteger();
        AtomicBoolean healthy = new AtomicBoolean();
        DeadlineWaiter waiter = (deadline, clk) -> {
            waits.incrementAndGet();
            if (!healthy.get()) {
                throw new IllegalStateException("waiter");
            }
        };
        AtomicReference<Thread> polling = new AtomicReference<>();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            polling.set(t);
            return t;
        };
        Duration sampleRate = Duration.ofMillis(200);
        ResourceMonitor m = new ResourceMonitor(
                TopologyUpdater.from(new TopologyMapper(new BitSet())), sampleRate, snapshots, clock, waiter, factory);
        CountDownLatch published = new CountDownLatch(1);
        m.addListener(u -> published.countDown());
        try {
            m.start();
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (waits.get() < 1 && !Thread.currentThread().isInterrupted()) {
                    Thread.yield();
                }
            });
            Thread t = polling.get();
            long cpuBefore = bean.getThreadCpuTime(t.getId());
            int waitsBefore = waits.get();
            long wallStart = System.nanoTime();
            Thread.sleep(200); // lets wall time pass only
            long elapsedNs = System.nanoTime() - wallStart;
            long cpu = bean.getThreadCpuTime(t.getId()) - cpuBefore;
            assertTrue(cpu < 20_000_000L, "CPU ns burned with a failing waiter: " + cpu);
            long allowed = elapsedNs / sampleRate.toNanos() + 2;
            assertTrue(
                    waits.get() - waitsBefore <= allowed, "waiter retried in a spin: " + (waits.get() - waitsBefore));
            healthy.set(true);
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(published.await(5, TimeUnit.SECONDS)));
        } finally {
            m.close();
        }
    }

    @Test
    void requireSnapshotterRejectsNull() {
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> ResourceMonitor.requireSnapshotter(null));
        assertTrue(e.getMessage().startsWith("resource sampling unavailable on "), e.getMessage());
        IncrementingSnapshotProvider p = new IncrementingSnapshotProvider();
        assertSame(p, ResourceMonitor.requireSnapshotter(p));
    }

    @Test
    void pollingThreadTerminatesAfterClose() {
        AtomicReference<Thread> polling = new AtomicReference<>();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            polling.set(t);
            return t;
        };
        ResourceMonitor m = monitor(new IncrementingSnapshotProvider(), factory);
        m.start();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(m.close(Duration.ofSeconds(4))));
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> polling.get().join());
        assertFalse(polling.get().isAlive());
    }

    @Test
    void pollingThreadTerminatesAfterSelfCallClose() {
        AtomicReference<Thread> polling = new AtomicReference<>();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            polling.set(t);
            return t;
        };
        ResourceMonitor m = monitor(new IncrementingSnapshotProvider(), factory);
        CountDownLatch closed = new CountDownLatch(1);
        m.addListener(u -> {
            m.close();
            closed.countDown();
        });
        m.start();
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertTrue(m.close(Duration.ofSeconds(4)));
            polling.get().join();
        });
        assertFalse(polling.get().isAlive());
    }

    private static final class TestError extends Error {
        private static final long serialVersionUID = 1L;
    }

    @Test
    void errorFromProviderClosesMonitor() {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch aboutToThrow = new CountDownLatch(1);
        AtomicReference<Thread> polling = new AtomicReference<>();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r);
            polling.set(t);
            return t;
        };
        IncrementingSnapshotProvider snapshots = new IncrementingSnapshotProvider() {
            @Override
            public SystemSnapshot getSnapshot() {
                if (calls.getAndIncrement() >= 1) {
                    aboutToThrow.countDown();
                    throw new TestError();
                }
                return super.getSnapshot();
            }
        };
        ResourceMonitor m = monitor(snapshots, factory);
        m.start();
        // No close() before the join: the Error path alone must terminate the poller and close the monitor.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertTrue(aboutToThrow.await(5, TimeUnit.SECONDS));
            polling.get().join();
        });
        assertFalse(polling.get().isAlive());
        // The poller's Error path closes via closeInternal(0) and its finally block completes
        // CLOSING -> CLOSED before the thread exits, so this holds without any close() from the test.
        assertEquals(ResourceMonitor.CLOSED_FOR_TEST, m.stateForTest());
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertTrue(m.close(Duration.ofSeconds(4))));
    }

    private static class IncrementingSnapshotProvider implements SystemSnapshotProvider {

        private final long startTime = System.nanoTime();
        private final int cpuCount = Math.max(SystemInfo.getCpuCount(), 1);
        private final BitSet effectiveCpus = effectiveCpus();
        private final AtomicInteger samples = new AtomicInteger();

        private static BitSet effectiveCpus() {
            BitSet cpus = (BitSet) SystemInfo.getCpuSet().clone();
            if (cpus.isEmpty()) {
                cpus.set(0);
            }
            return cpus;
        }

        @Override
        public SystemSnapshot getSnapshot() {
            int sample = this.samples.getAndIncrement();
            int pressureLength = Math.max(this.cpuCount, this.effectiveCpus.length());
            return SystemSnapshot.create(
                    this.startTime + SECONDS.toNanos(sample),
                    this.cpuCount,
                    1.0,
                    100_000,
                    SECONDS.toNanos(sample) / 2,
                    SECONDS.toNanos(sample) / 10,
                    UnmodifiableBitSet.wrap((BitSet) this.effectiveCpus.clone()),
                    new double[pressureLength],
                    new long[] {1_000, 600, 100},
                    sample * 200L);
        }
    }
}
