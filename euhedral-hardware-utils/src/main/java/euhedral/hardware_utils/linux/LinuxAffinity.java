package euhedral.hardware_utils.linux;

import euhedral.hardware_utils.internal.JNIClassLoader;
import euhedral.hardware_utils.common.OSName;
import euhedral.hardware_utils.internal.ThreadPinner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LinuxAffinity extends ThreadPinner {

    public static final LinuxAffinity INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(LinuxAffinity.class);

    static {
        LinuxAffinity instance = null;

        if (OSName.isLinux()) {
            try {
                JNIClassLoader.load(LinuxAffinity.class);
                instance = new LinuxAffinity();
            } catch (Throwable t) {
                LOGGER.error("Failed to load the linux_affinity library.", t);
            }
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
        } catch (Throwable t) {
            LOGGER.error("Failed to set Linux timer_slack.", t);
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
