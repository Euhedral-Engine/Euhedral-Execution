package euhedral.hardware_utils.windows;

import euhedral.hardware_utils.common.JNIClassLoader;
import euhedral.hardware_utils.common.OSName;
import euhedral.hardware_utils.common.ThreadPinner;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WindowsAffinity extends ThreadPinner {

    public static final WindowsAffinity INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowsAffinity.class);
    private static final AtomicBoolean WIN_RES_SET = new AtomicBoolean(false);
    private static volatile int windowsResolution100ns;

    static {
        WindowsAffinity instance = null;

        if (OSName.isWindows()) {
            try {
                JNIClassLoader.load(WindowsAffinity.class);
                instance = new WindowsAffinity();
            } catch (Throwable t) {
                LOGGER.error("Failed to load windows_affinity.", t);
            }
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

        try {
            int res = (int) Math.min(Integer.MAX_VALUE, nanos) / 100;
            int applied = ntSetTimerResolution(res, true);

            LOGGER.info("Windows: Requested resolution: {} Applied Resolution: {}",
                    res, applied * 100L);

            windowsResolution100ns = applied;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    ntSetTimerResolution(windowsResolution100ns, false);
                } catch (Throwable ignored) {
                }
            }, "win-timer-release"));
        } catch (Throwable t) {
            LOGGER.error("Failed to set Windows timer resolution.", t);
            WIN_RES_SET.set(false);
            return false;
        }
        return true;
    }

    private static native int setThreadAffinity(long[] masks);

    private static native int ntSetTimerResolution(int resolution, boolean set);
}
