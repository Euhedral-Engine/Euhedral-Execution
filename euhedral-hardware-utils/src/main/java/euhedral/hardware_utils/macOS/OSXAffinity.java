package euhedral.hardware_utils.macOS;

import euhedral.hardware_utils.internal.JNIClassLoader;
import euhedral.hardware_utils.common.OSName;
import euhedral.hardware_utils.internal.ThreadPinner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OSXAffinity extends ThreadPinner {
    private static final Logger LOGGER = LoggerFactory.getLogger(OSXAffinity.class);

    public static final OSXAffinity INSTANCE;

    static {
        OSXAffinity instance = null;

        if(OSName.isMacOS()) {
            try {
                JNIClassLoader.load(OSXAffinity.class);
                instance = new OSXAffinity();
            } catch (Throwable t) {
                LOGGER.error("Failed to load the windows_affinity.cpp library.", t);
            }
        }
        INSTANCE = instance;
    }

    private OSXAffinity() {

    }

    @Override
    public native int getCpu();

    @Override
    public boolean setAffinity(long[] masks) {
        int status = setThreadAffinity(masks);
        if (status != 0) {
            LOGGER.error("Failed to set thread affinity: ERR_CODE: {}", status);
        }

        return status == 0;
    }

    @Override
    public boolean setTimerResolution(long nanos) {
        if(nanos < 0) {
            throw new RuntimeException("Cannot set negative resolution: " + nanos);
        }
        nanos = Math.max(1, nanos);
        return setThreadTickPolicy(nanos);
    }

    private static native int setThreadAffinity(long[] masks);

    private static native boolean setThreadTickPolicy(long nanos);
}
