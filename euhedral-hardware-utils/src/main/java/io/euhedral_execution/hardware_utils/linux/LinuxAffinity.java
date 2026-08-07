package io.euhedral_execution.hardware_utils.linux;

import io.euhedral_execution.hardware_utils.AffinityCapability;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;
import io.euhedral_execution.hardware_utils.internal.ThreadPinner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Native Linux thread affinity, CPU discovery, and timer slack provider facade.
public final class LinuxAffinity extends ThreadPinner {

    public static final LinuxAffinity INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(LinuxAffinity.class));

    static {
        boolean loaded = false;
        try {
            JNIClassLoader.load();
            loaded = true;
        } catch (Throwable t) {
            LOGGER.warn("Failed to load JNI library for LinuxAffinity", t);
        }

        LinuxAffinity instance = null;
        if (OSName.isLinux()) {
            instance = new LinuxAffinity(loaded);
        }
        INSTANCE = instance;
    }

    private static native int getThreadAffinity(long[] masks);

    private LinuxAffinity(boolean jniLoaded) {
        super(jniLoaded ? AffinityCapability.EXACT : AffinityCapability.UNSUPPORTED, null,
                jniLoaded);
    }

    /// Returns the logical CPU ID of the calling thread.
    @Override
    public native int getCpu();

    /// Captures the calling thread's current exact CPU mask without changing it.
    @Override
    public long[] captureAffinity() {
        if (capability() == AffinityCapability.UNSUPPORTED) {
            return null;
        }
        int expectedWords = Math.max(1, (SystemInfo.getCpuCount() + 63) >>> 6);
        int bufferWords = Math.max(16, expectedWords);
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
            LOGGER.error("Failed to get Linux thread affinity.", e);
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

    /// Applies one complete little-endian logical CPU mask through the Linux JNI boundary.
    @Override
    public boolean setAffinity(long[] masks) {
        return LinuxAffinityCalls.apply(masks, LinuxAffinity::setThreadAffinity);
    }

    private static native int setThreadAffinity(long[] masks);

    /// Configures process timer slack resolution in nanoseconds.
    @Override
    public boolean setTimerResolution(long nanos) {
        if (nanos < 0) {
            throw new RuntimeException("Cannot set negative resolution: " + nanos);
        }

        nanos = Math.max(1L, nanos);
        try {
            int result = prctl(nanos);
            if (result != 0) {
                LOGGER.error("Linux prctl failed with return code: {}", result);
                return false;
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("Failed to set Linux timer_slack.", e);
            return false;
        }
        return true;
    }

    private static native int prctl(long nanos);
}
