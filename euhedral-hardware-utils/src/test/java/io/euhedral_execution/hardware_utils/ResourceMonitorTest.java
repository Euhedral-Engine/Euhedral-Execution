package io.euhedral_execution.hardware_utils;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.internal.monitor.DeadlineWaiter;
import io.euhedral_execution.hardware_utils.internal.monitor.MonotonicClock;
import io.euhedral_execution.hardware_utils.internal.monitor.TopologyUpdater;
import java.time.Duration;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
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
                utilization -> topologyUpdates.incrementAndGet(), Duration.ofMillis(200),
                snapshots, clock, (deadline, clk) -> {}, Thread::new)) {
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

    private static final class IncrementingSnapshotProvider implements SystemSnapshotProvider {

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
