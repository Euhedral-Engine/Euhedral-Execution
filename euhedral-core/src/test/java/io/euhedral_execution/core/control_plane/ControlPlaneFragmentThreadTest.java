package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentActionPicker;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import java.util.BitSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

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

        PinnedThreadExecutor executor = PinnedThreadExecutor.getOrSetIfAbsent(
                cpu, "regular-thread-owner", Thread.NORM_PRIORITY, true);
        assertFalse(executor.submit(
                () -> Thread.currentThread() instanceof FlowThread).get(1, TimeUnit.SECONDS));

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
}
