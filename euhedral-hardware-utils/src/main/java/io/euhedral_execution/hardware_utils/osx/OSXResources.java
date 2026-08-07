package io.euhedral_execution.hardware_utils.osx;

import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;
import io.euhedral_execution.hardware_utils.macos.MacosResources;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Legacy macOS resource provider implementing SystemSnapshotProvider and delegating to MacosResources.
public final class OSXResources implements SystemSnapshotProvider {

    public static final OSXResources INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(OSXResources.class));

    static {
        JNIClassLoader.load();

        OSXResources instance = null;
        if (OSName.isMacOS()) {
            try {
                instance = new OSXResources();
            } catch (Throwable t) {
                LOGGER.error("Failed to initialize.", t);
            }
        }

        INSTANCE = instance;
    }

    private final AtomicBoolean wip = new AtomicBoolean(false);
    private final AtomicReference<SystemSnapshot> snapshot = new AtomicReference<>();
    private final UnmodifiableBitSet effectiveCpus;
    private final long[] lastCpuTimes = new long[2];
    private long lastTimeNs = -1;

    /// Constructs an OSXResources provider instance.
    public OSXResources() throws Throwable {
        int available = Runtime.getRuntime().availableProcessors();
        BitSet set = new BitSet(available);
        set.set(0, available);
        this.effectiveCpus = UnmodifiableBitSet.wrap(set);
    }

    @Override
    public SystemSnapshot getSnapshot() {
        if (MacosResources.INSTANCE != null) {
            return MacosResources.INSTANCE.getSnapshot();
        }

        if (!this.wip.compareAndSet(false, true)) {
            while (this.wip.get()) {
                Thread.onSpinWait();
            }
            return this.snapshot.get();
        }

        SystemSnapshot snapshot = this.snapshot.get();
        try {
            long now = System.nanoTime();

            long[] cpuTimes = getCpuTimes();
            long totalCpuTime = cpuTimes.length > 0 ? cpuTimes[0] : 0;

            long cpuUsageDelta = 0;
            long dtCpu = now - lastTimeNs;

            if (lastTimeNs > 0 && dtCpu > 0) {
                long prev = lastCpuTimes[0];
                cpuUsageDelta = Math.max(0, totalCpuTime - prev);
            }

            lastCpuTimes[0] = totalCpuTime;
            lastTimeNs = now;

            long cpuThrottle = 0;

            int availableCpus = Runtime.getRuntime().availableProcessors();

            double systemLoad = getSystemCpuLoad();

            double processUtil = (double) cpuUsageDelta / (dtCpu * availableCpus);
            processUtil = Math.min(1.0, Math.max(0.0, processUtil));
            double pressure = systemLoad * (1.0 - processUtil);

            double[] pressurePerCpu = new double[availableCpus];
            Arrays.fill(pressurePerCpu, pressure);

            long ioBytes = getIoBytes();

            snapshot = SystemSnapshot.create(
                    now,
                    availableCpus,
                    availableCpus,
                    1L,
                    cpuUsageDelta,
                    cpuThrottle,
                    effectiveCpus,
                    pressurePerCpu,
                    getMemorySnapshot(),
                    ioBytes
            );
        } catch (Exception e) {
            LOGGER.error("Error generating SystemSnapshot.", e);
        } finally {
            this.snapshot.set(snapshot);
            this.wip.set(false);
        }
        return snapshot;
    }

    /// Legacy native method to query CPU usage times.
    public static native long[] getCpuTimes();

    /// Legacy native method to query system CPU load.
    public static native double getSystemCpuLoad();

    /// Legacy native method to query memory snapshot.
    public static native long[] getMemorySnapshot();

    /// Legacy native method to query cumulative disk I/O bytes.
    public static native long getIoBytes();

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
}
