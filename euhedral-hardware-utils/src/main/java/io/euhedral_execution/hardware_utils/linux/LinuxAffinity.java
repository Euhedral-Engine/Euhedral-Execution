package io.euhedral_execution.hardware_utils.linux;

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

    @Override
    public boolean setAffinity(long[] masks) {
        int status = setThreadAffinity(masks);
        if (status != 0) {
            LOGGER.error("Failed to set thread affinity: ERR_CODE: {}", status);
        }

        return status == 0;
    }

    private static native int setThreadAffinity(long[] masks);

    private static native int prctl(long nanos);
}
