package io.euhedral_execution.hardware_utils.linux;

import io.euhedral_execution.hardware_utils.AffinityCapability;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;
import io.euhedral_execution.hardware_utils.internal.ThreadPinner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LinuxAffinity extends ThreadPinner {

    public static final LinuxAffinity INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(LinuxAffinity.class));

    static {
        JNIClassLoader.load();

        LinuxAffinity instance = null;
        if (OSName.isLinux()) {
            instance = new LinuxAffinity();
        }
        INSTANCE = instance;
    }

    private LinuxAffinity() {
        super(AffinityCapability.UNSUPPORTED, null, true);
    }

    @Override
    public native int getCpu();

    @Override
    public boolean setTimerResolution(long nanos) {
        if(nanos < 0) {
            throw new RuntimeException("Cannot set negative resolution: " + nanos);
        }

        nanos = Math.max(1, nanos);
        try {
            int result = prctl(nanos);
            if (result != 0) {
                LOGGER.error("Linux prctl failed with return code: {}", result);
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to set Linux timer_slack.", e);
            return false;
        }
        return true;
    }

    /// Applies one complete little-endian logical CPU mask through the Linux JNI boundary.
    @Override
    public boolean setAffinity(long[] masks) {
        return LinuxAffinityCalls.apply(masks, LinuxAffinity::setThreadAffinity);
    }

    private static native int setThreadAffinity(long[] masks);

    private static native int prctl(long nanos);

}
