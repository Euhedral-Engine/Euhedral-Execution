package io.euhedral_execution.hardware_utils.windows;

import io.euhedral_execution.hardware_utils.AffinityCapability;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;
import io.euhedral_execution.hardware_utils.internal.ThreadPinner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Native Windows thread affinity, CPU discovery, and timer resolution provider facade.
public final class WindowsAffinity extends ThreadPinner {

    public static final WindowsAffinity INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(WindowsAffinity.class));
    private static final AtomicBoolean WIN_RES_SET = new AtomicBoolean(false);
    private final AtomicInteger windowsResolution100ns = new AtomicInteger(-1);

    static {
        boolean loaded = false;
        try {
            JNIClassLoader.load();
            loaded = true;
        } catch (Throwable t) {
            LOGGER.warn("Failed to load JNI library for WindowsAffinity", t);
        }

        WindowsAffinity instance = null;
        if (OSName.isWindows()) {
            instance = new WindowsAffinity(loaded);
        }
        INSTANCE = instance;
    }

    private WindowsAffinity(boolean jniLoaded) {
        super(jniLoaded ? AffinityCapability.EXACT : AffinityCapability.UNSUPPORTED, null, jniLoaded);
    }

    /// Returns the global logical CPU ID of the calling thread.
    @Override
    public native int getCpu();

    /// Captures the calling thread's current exact CPU mask without changing it.
    @Override
    public long[] captureAffinity() {
        if (capability() == AffinityCapability.UNSUPPORTED) {
            return null;
        }
        int expectedWords = Math.max(1, (SystemInfo.getCpuCount() + 63) >>> 6);
        int bufferWords = Math.max(64, expectedWords);
        long[] mask = new long[bufferWords];
        try {
            int result = getThreadAffinity(mask);
            if (result == 0) {
                int len = bufferWords;
                while (len > 0 && mask[len - 1] == 0) {
                    len--;
                }
                if (len == 0) {
                    return null;
                }
                return java.util.Arrays.copyOf(mask, len);
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("Failed to get Windows thread affinity.", e);
        }
        return null;
    }

    /// Atomically applies every CPU bit in an exact mask.
    @Override
    public boolean applyExact(long[] mask) {
        return setAffinity(mask);
    }

    /// Restores a mask previously returned by captureAffinity().
    @Override
    public boolean restoreExact(long[] mask) {
        return setAffinity(mask);
    }

    /// Applies little-endian processor group masks.
    @Override
    public boolean setAffinity(long[] masks) {
        return WindowsAffinityCalls.apply(masks, WindowsAffinity::setThreadAffinity);
    }

    @Override
    public boolean setTimerResolution(long nanos) {
        if (!WIN_RES_SET.compareAndSet(false, true)) {
            LOGGER.error("Windows timer resolution has already been set.");
            return false;
        }

        if (nanos < 0) {
            throw new IllegalArgumentException("Cannot set negative resolution: " + nanos);
        }
        nanos = Math.max(nanos, 1L);

        int res = (int) (Math.min(Integer.MAX_VALUE, nanos) / 100L);
        int applied = ntSetTimerResolution(res, true);

        if (!this.windowsResolution100ns.compareAndSet(-1, applied)) {
            LOGGER.error("Failed to set timer resolution. Already set. Desired {}", nanos);
        }

        try {
            LOGGER.info("Windows: Requested resolution: {} Applied Resolution: {}",
                    res, applied * 100L);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    ntSetTimerResolution(this.windowsResolution100ns.getAcquire(), false);
                } catch (Exception ignored) {
                    // Ignore on shutdown
                }
            }, "win-timer-release"));
        } catch (Exception e) {
            LOGGER.error("Failed to set Windows timer resolution.", e);
            WIN_RES_SET.set(false);
            return false;
        }
        return true;
    }

    private static native int setThreadAffinity(long[] masks);

    private static native int getThreadAffinity(long[] masks);

    private static native int ntSetTimerResolution(int resolution, boolean set);
}
