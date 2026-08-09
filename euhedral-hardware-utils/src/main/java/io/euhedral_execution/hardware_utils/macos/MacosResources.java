package io.euhedral_execution.hardware_utils.macos;

import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MacosResources implements SystemSnapshotProvider {

  public static final MacosResources INSTANCE;
  private static final Logger LOGGER =
      LoggerFactory.getLogger(Constants.getLoggerName(MacosResources.class));
  private static final VarHandle LOCK_STATE;
  private static final long LOG_RATE_LIMIT_NS = 60_000_000_000L;

  static {
    JNIClassLoader.load();

    MacosResources instance = null;
    if (OSName.isMacOS()) {
      try {
        instance = new MacosResources();
      } catch (Throwable t) {
        LOGGER.error("Failed to initialize MacosResources.", t);
      }
    }
    INSTANCE = instance;

    try {
      LOCK_STATE =
          MethodHandles.lookup().findVarHandle(MacosResources.class, "lockState", int.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /// Interface abstraction for native macOS resource probes to enable unit testing.
  public interface MacosResourceProbe {
    /// Queries process CPU usage nanoseconds and cumulative disk I/O bytes. Array length must be >=
    /// 2. Index 0 receives CPU usage ns, index 1 receives disk I/O bytes.
    boolean getProcessRusage(long[] outCpuAndIoBytes);

    /// Queries system memory and process task memory. Array length must be >= 3. Index 0 receives
    /// total physical RAM, index 1 receives resident size, index 2 receives virtual size.
    boolean getTaskMemory(long[] outMemory);

    /// Queries NSProcessInfo thermal state. Returns 0 for NOMINAL, 1 for FAIR, 2 for SERIOUS, 3 for
    /// CRITICAL.
    int getThermalState();

    /// Queries NSProcessInfo low-power mode flag.
    boolean isLowPowerMode();

    /// Queries Mach timebase conversion factors numer and denom into outNumerDenom array (length >=
    /// 2).
    boolean getMachTimebase(int[] outNumerDenom);
  }

  /// Default native probe delegating to JNI bindings.
  public static final class DefaultNativeProbe implements MacosResourceProbe {
    public static final DefaultNativeProbe INSTANCE = new DefaultNativeProbe();

    @Override
    public boolean getProcessRusage(long[] outCpuAndIoBytes) {
      try {
        return MacosResources.getProcessRusageNative(outCpuAndIoBytes);
      } catch (Throwable ignored) {
        return false;
      }
    }

    @Override
    public boolean getTaskMemory(long[] outMemory) {
      try {
        return MacosResources.getTaskMemoryNative(outMemory);
      } catch (Throwable ignored) {
        return false;
      }
    }

    @Override
    public int getThermalState() {
      try {
        return MacosResources.getThermalStateNative();
      } catch (Throwable ignored) {
        return 0;
      }
    }

    @Override
    public boolean isLowPowerMode() {
      try {
        return MacosResources.isLowPowerModeNative();
      } catch (Throwable ignored) {
        return false;
      }
    }

    @Override
    public boolean getMachTimebase(int[] outNumerDenom) {
      try {
        return MacosResources.getMachTimebaseNative(outNumerDenom);
      } catch (Throwable ignored) {
        return false;
      }
    }
  }

  /// Native method to query process CPU usage nanoseconds and cumulative disk I/O bytes.
  public static native boolean getProcessRusageNative(long[] outCpuAndIoBytes);

  /// Native method to query system total memory and process task resident / virtual memory.
  public static native boolean getTaskMemoryNative(long[] outMemory);

  /// Native method to query NSProcessInfo thermal state enum.
  public static native int getThermalStateNative();

  /// Native method to query NSProcessInfo low-power mode status.
  public static native boolean isLowPowerModeNative();

  /// Native method to query Mach timebase numer and denom conversion factors.
  public static native boolean getMachTimebaseNative(int[] outNumerDenom);

  /// Legacy native method to query CPU usage times.
  public static native long[] getCpuTimes();

  /// Legacy native method to query system CPU load.
  public static native double getSystemCpuLoad();

  /// Legacy native method to query memory snapshot.
  public static native long[] getMemorySnapshot();

  /// Legacy native method to query cumulative disk I/O bytes.
  public static native long getIoBytes();

  private final MacosResourceProbe probe;

  @SuppressWarnings("unused")
  private volatile int lockState = 0;

  private long lastZeroDivLoggedNs = 0;

  /// Constructs a MacosResources provider with default native probes.
  public MacosResources() {
    this(DefaultNativeProbe.INSTANCE);
  }

  /// Constructs a MacosResources provider with a custom resource probe.
  public MacosResources(MacosResourceProbe probe) {
    this.probe = probe != null ? probe : DefaultNativeProbe.INSTANCE;
  }

  private void acquireLock() {
    int backoff = 0;
    while (!LOCK_STATE.compareAndSet(this, 0, 1)) {
      if (backoff < 10) {
        Thread.onSpinWait();
      } else if (backoff < 100) {
        Thread.yield();
      } else {
        LockSupport.parkNanos(1_000L);
      }
      backoff++;
    }
  }

  private void releaseLock() {
    LOCK_STATE.setRelease(this, 0);
  }

  /// Converts Mach absolute time ticks to nanoseconds using timebase scaling and zero-division
  /// protection.
  public long ticksToNanos(long ticks) {
    int[] numerDenom = new int[2];
    if (probe.getMachTimebase(numerDenom) && numerDenom[1] > 0) {
      return (ticks * (long) numerDenom[0]) / (long) numerDenom[1];
    } else {
      logZeroDivisionWarning();
      return ticks;
    }
  }

  private void logZeroDivisionWarning() {
    long now = System.nanoTime();
    if (now - lastZeroDivLoggedNs >= LOG_RATE_LIMIT_NS) {
      LOGGER.warn(
          "Invalid Mach timebase denom (0 or unavailable), falling back to 1:1 tick-to-nanosecond"
              + " ratio.");
      lastZeroDivLoggedNs = now;
    }
  }

  @Override
  public SystemSnapshot getSnapshot() {
    acquireLock();
    try {
      long now = System.nanoTime();
      int availableCpus = Math.max(1, SystemInfo.getCpuCount());
      BitSet effectiveSet = new BitSet(availableCpus);
      effectiveSet.set(0, availableCpus);

      long[] rusage = new long[2];
      boolean rusageValid = probe.getProcessRusage(rusage);
      long cpuUsageNs = rusageValid ? rusage[0] : 0L;
      long ioBytes = rusageValid ? rusage[1] : 0L;

      long[] memStats = new long[3];
      boolean memValid = probe.getTaskMemory(memStats);
      long totalRamBytes = memValid ? memStats[0] : 0L;
      long residentBytes = memValid ? memStats[1] : 0L;
      long virtualBytes = memValid ? memStats[2] : 0L;
      long sharedBytes = Math.max(0L, virtualBytes - residentBytes);

      double[] pressurePerCpu = new double[availableCpus];
      Arrays.fill(pressurePerCpu, 0.0);

      long[] memSnap = new long[] {totalRamBytes, residentBytes, sharedBytes};

      return SystemSnapshot.create(
          now,
          availableCpus,
          availableCpus,
          100_000L,
          cpuUsageNs,
          0L,
          UnmodifiableBitSet.wrap(effectiveSet),
          pressurePerCpu,
          memSnap,
          ioBytes);
    } finally {
      releaseLock();
    }
  }
}
