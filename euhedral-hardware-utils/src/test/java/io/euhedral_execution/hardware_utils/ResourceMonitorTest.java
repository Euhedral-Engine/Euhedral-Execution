package io.euhedral_execution.hardware_utils;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import java.time.Duration;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResourceMonitorTest {

    @Test
    void calculatesUtilizationFromInjectedSnapshots() {
        IncrementingSnapshotProvider snapshots = new IncrementingSnapshotProvider();
        ResourceMonitor monitor = new ResourceMonitor(
                new TopologyMapper(new BitSet()), Duration.ofMillis(200), snapshots);

        try {
            HardwareUtilization utilization = monitor.getUtilization();

            assertEquals(1.0, utilization.quotaCpus());
            assertTrue(utilization.quotaCpuUsage() > 0.45);
            assertTrue(utilization.quotaCpuUsage() < 0.55);
            assertEquals(0.1, utilization.cpuThrottleRatio(), 0.01);
            assertEquals(1_000, utilization.globalMemoryPool());
            assertEquals(0.5, utilization.totalMemoryUtilization());
            assertEquals(200.0, utilization.diskIOBytesPerSecond(), 0.01);
            assertEquals(snapshots.effectiveCpus, utilization.globalEffectiveCpus());
            assertEquals(3, snapshots.samples.get());
        } finally {
            monitor.close();
        }
    }

    private static final class IncrementingSnapshotProvider implements SystemSnapshotProvider {

        private final long startTime = System.nanoTime();
        private final int cpuCount = Math.max(SystemInfo.getCpuCount(), 1);
        private final BitSet effectiveCpus = effectiveCpus();
        private final AtomicInteger samples = new AtomicInteger();

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
                    new long[]{1_000, 600, 100},
                    sample * 200L);
        }

        private static BitSet effectiveCpus() {
            BitSet cpus = (BitSet) SystemInfo.getCpuSet().clone();
            if (cpus.isEmpty()) {
                cpus.set(0);
            }
            return cpus;
        }
    }
}
