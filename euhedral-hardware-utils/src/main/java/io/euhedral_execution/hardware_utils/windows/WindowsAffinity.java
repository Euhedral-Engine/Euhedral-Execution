package io.euhedral_execution.hardware_utils.windows;

import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;
import io.euhedral_execution.hardware_utils.internal.ThreadPinner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WindowsAffinity extends ThreadPinner {

    public static final WindowsAffinity INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(WindowsAffinity.class));
    private static final AtomicBoolean WIN_RES_SET = new AtomicBoolean(false);
    private final AtomicInteger windowsResolution100ns = new AtomicInteger(-1);

    static {
        JNIClassLoader.load();

        WindowsAffinity instance = null;
        if (OSName.isWindows()) {
            instance = new WindowsAffinity();
        }
        INSTANCE = instance;
    }

    private WindowsAffinity() {
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
        if (!WIN_RES_SET.compareAndSet(false, true)) {
            LOGGER.error("Windows timer resolution has already been set.");
            return false;
        }

        if(nanos < 0) {
            throw new RuntimeException("Cannot set negative resolution: " + nanos);
        }
        nanos = Math.max(nanos, 1);

        int res = (int) Math.min(Integer.MAX_VALUE, nanos) / 100;
        int applied = ntSetTimerResolution(res, true);

        if(!this.windowsResolution100ns.compareAndSet(-1, applied)) {
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

    private static native int ntSetTimerResolution(int resolution, boolean set);
}
