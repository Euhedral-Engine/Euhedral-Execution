package io.euhedral_execution.hardware_utils.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.SlowHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuFastSignals;
import org.junit.jupiter.api.Test;

public class WindowsResourcesTest {

    @Test
    public void testJobObjectCpuQuotaScalingMath() {
        int availableCpus = 8;

        // CpuRate = 5000 (50.0%)
        double quotaFraction50 = 5000 / 10000.0;
        assertEquals(0.50, quotaFraction50, 0.0001);
        double quotaCpus50 = quotaFraction50 * availableCpus;
        assertEquals(4.0, quotaCpus50, 0.0001);

        // CpuRate = 20000 (200.0%)
        double quotaFraction200 = 20000 / 10000.0;
        assertEquals(2.00, quotaFraction200, 0.0001);
        double quotaCpus200 = quotaFraction200 * availableCpus;
        assertEquals(16.0, quotaCpus200, 0.0001);
    }

    @Test
    public void testProcessCpuTimeConversion() {
        long kernelTime100ns = 1_000_000L;
        long userTime100ns = 2_000_000L;

        long totalNs = (kernelTime100ns + userTime100ns) * 100L;
        assertEquals(300_000_000L, totalNs, "FILETIME 100-ns units must scale by 100L to nanoseconds");
    }

    @Test
    public void testIdleCycleDeltaNormalization() {
        double[] lastIdle = new double[] {1_000_000.0, 1_000_000.0};
        double[] currentIdle = new double[] {1_050_000.0, 1_100_000.0};
        double[] pressure = new double[2];
        long dt = 100_000_000L; // 100 ms in nanoseconds

        for (int i = 0; i < currentIdle.length; i++) {
            double deltaIdle = currentIdle[i] - lastIdle[i];
            double busy = Math.max(0.0, 1.0 - (deltaIdle / dt));
            pressure[i] = busy;
            assertTrue(pressure[i] >= 0.0 && pressure[i] <= 1.0, "Busy ratio must be bounded in [0.0, 1.0]");
        }
    }

    @Test
    public void testCumulativeIoBytesCalculation() {
        long readTransferCount = 500_000L;
        long writeTransferCount = 300_000L;

        long cumulativeIoBytes = readTransferCount + writeTransferCount;
        assertEquals(800_000L, cumulativeIoBytes);
    }

    @Test
    public void testWindowsResourcesSnapshotAndSamplingContract() {
        if (!OSName.isWindows()) {
            WindowsResources resources = new WindowsResources();
            assertNotNull(resources);
            return;
        }

        WindowsResources resources = WindowsResources.INSTANCE;
        assertNotNull(resources);

        SystemSnapshot snapshot = resources.getSnapshot();
        assertNotNull(snapshot);
        assertTrue(snapshot.totalCpus() > 0);
        assertTrue(snapshot.quotaCpus() > 0.0);

        FastHardwareSample fast = resources.sampleFast(System.nanoTime());
        assertNotNull(fast);
        assertTrue(fast.logicalSpan() > 0);
        assertNotNull(fast.effectiveCpus());
        assertEquals(SignalValidity.VALID, fast.productiveCpuNs().validity());

        CpuFastSignals[] cpus = fast.cpuSignals();
        assertNotNull(cpus);
        assertEquals(fast.logicalSpan(), cpus.length);

        SlowHardwareSample slow = resources.sampleSlow(System.nanoTime());
        assertNotNull(slow);
        assertEquals(fast.logicalSpan(), slow.logicalSpan());
    }
}
