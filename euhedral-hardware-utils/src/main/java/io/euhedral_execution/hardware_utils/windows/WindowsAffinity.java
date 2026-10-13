package io.euhedral_execution.hardware_utils.windows;

import io.euhedral_execution.hardware_utils.affinity.AffinityCapability;
import io.euhedral_execution.hardware_utils.affinity.ThreadPinner;
import io.euhedral_execution.hardware_utils.nativelib.JNIClassLoader;
import io.euhedral_execution.hardware_utils.topology.SystemInfo;
import io.euhedral_execution.hardware_utils.util.OSName;
import io.euhedral_execution.hardware_utils.util.internal.Constants;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Native Windows thread affinity, CPU discovery, and timer resolution provider facade.
public final class WindowsAffinity extends ThreadPinner {

    public static final @Nullable WindowsAffinity INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(WindowsAffinity.class));

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

    private final AtomicInteger windowsResolution100ns = new AtomicInteger(-1);
    private final AtomicBoolean timerResolutionSet = new AtomicBoolean(false);
    private final TimerResolutionCall timerCall;

    private WindowsAffinity(boolean jniLoaded) {
        this(
                jniLoaded ? AffinityCapability.EXACT : AffinityCapability.UNSUPPORTED,
                jniLoaded,
                WindowsAffinity::ntSetTimerResolution);
    }

    /// Package-private seam so tests can replace the native timer call without Windows.
    WindowsAffinity(AffinityCapability capability, boolean jniLoaded, TimerResolutionCall timerCall) {
        super(capability, null, jniLoaded);
        this.timerCall = timerCall;
    }

    /// Raw timer-resolution call: returns the applied resolution in 100 ns units.
    @FunctionalInterface
    interface TimerResolutionCall {
        int apply(int resolution100ns, boolean set);
    }

    private static native int setThreadAffinity(long[] masks);

    private static native int getThreadAffinity(long[] masks);

    private static native int ntSetTimerResolution(int resolution, boolean set);

    /// Returns the global logical CPU ID of the calling thread.
    @Override
    public native int getCpu();

    /// Captures the calling thread's current exact CPU mask without changing it.
    @Override
    public long @Nullable [] captureAffinity() {
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

    /// Requests a process-wide timer resolution. Validation runs first, so a bad argument never
    /// consumes the one-shot slot. Native failures release the slot so a later call can retry.
    @Override
    public boolean setTimerResolution(long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("Cannot set negative resolution: " + nanos);
        }
        if (capability() == AffinityCapability.UNSUPPORTED) {
            return false;
        }
        if (!timerResolutionSet.compareAndSet(false, true)) {
            LOGGER.error("Windows timer resolution has already been set.");
            return false;
        }

        nanos = Math.max(nanos, 1L);
        int res = (int) (Math.min(Integer.MAX_VALUE, nanos) / 100L);
        int applied;
        try {
            applied = timerCall.apply(res, true);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("Failed to set Windows timer resolution.", e);
            timerResolutionSet.set(false);
            return false;
        }

        if (!this.windowsResolution100ns.compareAndSet(-1, applied)) {
            LOGGER.error("Failed to set timer resolution. Already set. Desired {}", nanos);
        }

        try {
            LOGGER.info("Windows: Requested resolution: {} Applied Resolution: {}", res, applied * 100L);

            Runtime.getRuntime()
                    .addShutdownHook(new Thread(
                            () -> {
                                try {
                                    timerCall.apply(this.windowsResolution100ns.getAcquire(), false);
                                } catch (RuntimeException | LinkageError ignored) {
                                    // Ignore on shutdown
                                }
                            },
                            "win-timer-release"));
        } catch (Exception e) {
            LOGGER.error("Failed to set Windows timer resolution.", e);
            // The resolution is already applied natively and no hook will release it, so release it now.
            // Best-effort: a failure here is logged and the slot is still freed.
            try {
                timerCall.apply(this.windowsResolution100ns.getAcquire(), false);
            } catch (RuntimeException | LinkageError releaseFailure) {
                LOGGER.error(
                        "Failed to restore Windows timer resolution after hook registration failure.", releaseFailure);
            }
            this.windowsResolution100ns.set(-1);
            timerResolutionSet.set(false);
            return false;
        }
        return true;
    }
}
