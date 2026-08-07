package io.euhedral_execution.hardware_utils.macos;

import io.euhedral_execution.hardware_utils.AffinityCapability;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;
import io.euhedral_execution.hardware_utils.internal.ThreadPinner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// macOS thread affinity provider enforcing Mach thread affinity tag mapping, single-locality cardinality rules,
/// safe timer resolution policy, and physical current CPU query semantics returning -1 (UNSUPPORTED).
public final class MacosAffinity extends ThreadPinner {

    public static final MacosAffinity INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(MacosAffinity.class));

    static {
        JNIClassLoader.load();

        MacosAffinity instance = null;
        if (OSName.isMacOS()) {
            try {
                instance = new MacosAffinity();
            } catch (Throwable t) {
                LOGGER.error("Failed to initialize MacosAffinity.", t);
            }
        }
        INSTANCE = instance;
    }

    private MacosAffinity() {
        super(AffinityCapability.LOCALITY_HINT, MacosAffinity::setThreadAffinityNative);
    }

    /// Queries physical CPU ID currently executing the thread.
    /// Returns -1 (UNSUPPORTED) on macOS platforms.
    @Override
    public native int getCpu();

    /// Applies process-visible CPU ordinals as macOS scheduler affinity tag hints.
    ///
    /// Requires single-locality mask (cardinality 1). Rejects multi-locality requests deterministically.
    @Override
    public boolean setAffinity(long[] masks) {
        return MacosAffinityCalls.applyOrdinal(masks, MacosAffinity::setThreadAffinityNative);
    }

    /// Configures thread timer resolution safely without altering thread scheduling policy or creating realtime traps.
    @Override
    public boolean setTimerResolution(long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("Cannot set negative resolution: " + nanos);
        }
        nanos = Math.max(1L, nanos);
        return setThreadTickPolicy(nanos);
    }

    public static int setThreadAffinityNative(long[] masks) {
        return setThreadAffinity(masks);
    }

    public static boolean setThreadTickPolicyNative(long nanos) {
        return setThreadTickPolicy(nanos);
    }

    private static native int setThreadAffinity(long[] masks);

    private static native boolean setThreadTickPolicy(long nanos);
}
