package io.euhedral_execution.hardware_utils.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.ThermalSeverity;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.SlowHardwareSample;
import io.euhedral_execution.hardware_utils.macos.MacosResources.MacosResourceProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class MacosResourcesTest {

    private static class MockProbe implements MacosResourceProbe {
        boolean rusageValid = true;
        long cpuUsageNs = 1_500_000L;
        long ioBytes = 3_000_000L;

        boolean taskMemoryValid = true;
        long totalRam = 16_000_000_000L;
        long resident = 100_000_000L;
        long virtual = 150_000_000L;

        int thermalState = 0;
        boolean lowPowerMode = false;

        boolean machTimebaseValid = true;
        int timebaseNumer = 1;
        int timebaseDenom = 1;

        @Override
        public boolean getProcessRusage(long[] outCpuAndIoBytes) {
            if (!rusageValid) return false;
            outCpuAndIoBytes[0] = cpuUsageNs;
            outCpuAndIoBytes[1] = ioBytes;
            return true;
        }

        @Override
        public boolean getTaskMemory(long[] outMemory) {
            if (!taskMemoryValid) return false;
            outMemory[0] = totalRam;
            outMemory[1] = resident;
            outMemory[2] = virtual;
            return true;
        }

        @Override
        public int getThermalState() {
            return thermalState;
        }

        @Override
        public boolean isLowPowerMode() {
            return lowPowerMode;
        }

        @Override
        public boolean getMachTimebase(int[] outNumerDenom) {
            if (!machTimebaseValid) return false;
            outNumerDenom[0] = timebaseNumer;
            outNumerDenom[1] = timebaseDenom;
            return true;
        }
    }

    @Test
    @DisplayName("Process CPU time accumulation returns sum of user and system time")
    public void testProcessCpuTimeAccumulation() {
        MockProbe probe = new MockProbe();
        probe.cpuUsageNs = 1_500_000L;
        probe.ioBytes = 3_000_000L;
        MacosResources resources = new MacosResources(probe);

        FastHardwareSample sample = resources.sampleFast(1_000_000_000L);
        assertNotNull(sample);
        assertEquals(SignalValidity.VALID, sample.productiveCpuNs().validity());
        assertEquals(1_500_000L, sample.productiveCpuNs().value());
    }

    @Test
    @DisplayName("Disk I/O byte accumulation returns sum of read and written bytes")
    public void testDiskIoByteAccumulation() {
        MockProbe probe = new MockProbe();
        probe.ioBytes = 5_242_880L;
        MacosResources resources = new MacosResources(probe);

        FastHardwareSample sample = resources.sampleFast(1_000_000_000L);
        assertNotNull(sample);
        assertEquals(SignalValidity.VALID, sample.ioSignals().productiveBytes().validity());
        assertEquals(5_242_880L, sample.ioSignals().productiveBytes().value());
    }

    @Test
    @DisplayName("Resident memory snapshot and shared memory underflow protection")
    public void testMemorySnapshotAndUnderflowProtection() {
        MockProbe probe = new MockProbe();
        probe.totalRam = 16_000_000_000L;
        probe.resident = 100_000_000L;
        probe.virtual = 80_000_000L; // virtual < resident tests underflow guard
        MacosResources resources = new MacosResources(probe);

        FastHardwareSample sample = resources.sampleFast(1_000_000_000L);
        assertNotNull(sample);
        assertEquals(SignalValidity.VALID, sample.memorySignals().hardLimitBytes().validity());
        assertEquals(16_000_000_000L, sample.memorySignals().hardLimitBytes().value());
        assertEquals(SignalValidity.VALID, sample.memorySignals().usageBytes().validity());
        assertEquals(100_000_000L, sample.memorySignals().usageBytes().value());
        // Underflow protection: virtual - resident = -20MB -> saturates to 0L
        assertEquals(SignalValidity.VALID, sample.memorySignals().inactiveFileBytes().validity());
        assertEquals(0L, sample.memorySignals().inactiveFileBytes().value());

        // Standard memory case where virtual > resident
        probe.virtual = 150_000_000L;
        FastHardwareSample sample2 = resources.sampleFast(2_000_000_000L);
        assertEquals(50_000_000L, sample2.memorySignals().inactiveFileBytes().value());
    }

    @Test
    @DisplayName("NSProcessInfo thermal state severity mapping to ThermalSeverity enum")
    public void testThermalSeverityStateMapping() {
        MockProbe probe = new MockProbe();

        probe.thermalState = 0; // Nominal
        SlowHardwareSample s0 = new MacosResources(probe).sampleSlow(1_000_000_000L);
        assertEquals(ThermalSeverity.NOMINAL, s0.systemSignals().thermalSeverity().value());
        assertEquals(SignalValidity.VALID, s0.systemSignals().thermalSeverity().validity());

        probe.thermalState = 1; // Fair
        SlowHardwareSample s1 = new MacosResources(probe).sampleSlow(1_000_000_000L);
        assertEquals(ThermalSeverity.FAIR, s1.systemSignals().thermalSeverity().value());

        probe.thermalState = 2; // Serious
        SlowHardwareSample s2 = new MacosResources(probe).sampleSlow(1_000_000_000L);
        assertEquals(ThermalSeverity.SERIOUS, s2.systemSignals().thermalSeverity().value());

        probe.thermalState = 3; // Critical
        SlowHardwareSample s3 = new MacosResources(probe).sampleSlow(1_000_000_000L);
        assertEquals(ThermalSeverity.CRITICAL, s3.systemSignals().thermalSeverity().value());
    }

    @Test
    @DisplayName("NSProcessInfo low-power mode signal mapping")
    public void testLowPowerModeSignalMapping() {
        MockProbe probe = new MockProbe();

        probe.lowPowerMode = false;
        SlowHardwareSample s0 = new MacosResources(probe).sampleSlow(1_000_000_000L);
        assertFalse(s0.systemSignals().lowPowerMode().value());
        assertEquals(SignalValidity.VALID, s0.systemSignals().lowPowerMode().validity());

        probe.lowPowerMode = true;
        SlowHardwareSample s1 = new MacosResources(probe).sampleSlow(1_000_000_000L);
        assertTrue(s1.systemSignals().lowPowerMode().value());
        assertEquals(SignalValidity.VALID, s1.systemSignals().lowPowerMode().validity());
    }

    @Test
    @DisplayName("Telemetry pressure isolation marks CPU and I/O pressure signals UNSUPPORTED")
    public void testTelemetryPressureIsolation() {
        MockProbe probe = new MockProbe();
        MacosResources resources = new MacosResources(probe);

        FastHardwareSample sample = resources.sampleFast(1_000_000_000L);
        assertEquals(SignalValidity.UNSUPPORTED, sample.scopePsiStallNs().validity());
        assertEquals(SignalValidity.UNSUPPORTED, sample.scopeReportedSchedulerStallRatio().validity());

        for (int i = 0; i < sample.logicalSpan(); i++) {
            assertEquals(SignalValidity.UNSUPPORTED, sample.cpuSignals()[i].psiStall().validity());
            assertEquals(SignalValidity.UNSUPPORTED, sample.cpuSignals()[i].reportedSchedulerStallRatio().validity());
        }

        assertEquals(SignalValidity.UNSUPPORTED, sample.ioSignals().stallNs().validity());
    }

    @Test
    @DisplayName("Mach timebase zero-division protection falls back to 1:1 scale")
    public void testMachTimebaseZeroDivisionProtection() {
        MockProbe probe = new MockProbe();
        probe.timebaseNumer = 1;
        probe.timebaseDenom = 0; // zero denom tests guard
        MacosResources resources = new MacosResources(probe);

        long nanos = resources.ticksToNanos(5_000L);
        assertEquals(5_000L, nanos); // Fallback to 1:1

        probe.timebaseNumer = 125;
        probe.timebaseDenom = 3;
        long scaledNanos = resources.ticksToNanos(300L);
        assertEquals(12_500L, scaledNanos);
    }

    @Test
    @DisplayName("Provider contract getSnapshot produces valid SystemSnapshot")
    public void testProviderContractGetSnapshot() {
        MockProbe probe = new MockProbe();
        probe.cpuUsageNs = 2_000_000L;
        probe.ioBytes = 4_000_000L;
        probe.totalRam = 16_000_000_000L;
        probe.resident = 100_000_000L;
        probe.virtual = 200_000_000L;
        MacosResources resources = new MacosResources(probe);

        SystemSnapshot snapshot = resources.getSnapshot();
        assertNotNull(snapshot);
        assertEquals(2_000_000L, snapshot.cpuUsage());
        assertEquals(4_000_000L, snapshot.diskIOBytes());
        assertEquals(16_000_000_000L, snapshot.memoryLimit());
        assertEquals(100_000_000L, snapshot.memoryUsage());
        assertEquals(100_000_000L, snapshot.inactiveFileMemory());
    }
}
