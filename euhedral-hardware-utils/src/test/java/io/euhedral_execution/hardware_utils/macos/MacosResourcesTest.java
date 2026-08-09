package io.euhedral_execution.hardware_utils.macos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
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
