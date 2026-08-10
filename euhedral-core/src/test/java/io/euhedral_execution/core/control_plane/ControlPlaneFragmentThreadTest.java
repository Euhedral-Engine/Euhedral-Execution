package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentActionPicker;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.common.SystemUtilization;
import java.util.BitSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.Mockito;

@Isolated
class ControlPlaneFragmentThreadTest {

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
}
