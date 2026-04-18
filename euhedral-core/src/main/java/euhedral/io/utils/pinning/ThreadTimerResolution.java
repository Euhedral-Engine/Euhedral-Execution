package euhedral.io.utils.pinning;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.ptr.IntByReference;
import java.util.concurrent.atomic.AtomicBoolean;

public class ThreadTimerResolution {

    private static final AtomicBoolean WIN_RES_SET = new AtomicBoolean(false);
    private static volatile int windowsResolution100ns;

    public static void setResolution(long nanos) {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux")) {
            Linux.setResolution(nanos);
        } else if (os.contains("win")) {
            Windows.setResolution(nanos);
        }
    }

    public interface Linux extends Library {

        Linux INSTANCE =
                System.getProperty("os.name").toLowerCase().contains("linux") ? Native.load("c",
                        Linux.class) : null;
        int TIMER_SLACK_OPTION = 29;

        static void setResolution(long nanos) {
            if (INSTANCE == null) {
                System.err.println("Cannot set timer_slack on a non Linux system.");
                return;
            }

            nanos = nanos <= 0 ? 1 : nanos;
            try {
                INSTANCE.prctl(TIMER_SLACK_OPTION, nanos, 0, 0, 0);
            } catch (Throwable t) {
                System.err.println("Failed to set Linux timer_slack: " + t);
            }
        }

        int prctl(int option, long arg2, long arg3, long arg4, long arg5);
    }

    /**
     * This sets the timer resolution system-wide on Windows. It can only be set once per process
     *
     */
    private interface Windows extends Library {

        Windows INSTANCE =
                System.getProperty("os.name").toLowerCase().contains("win") ? Native.load("ntdll",
                        Windows.class) : null;

        static void setResolution(long resolutionNs) {
            if (INSTANCE == null) {
                System.err.println("Cannot set Windows timer resolution on a non Windows system");
                return;
            }
            if (!WIN_RES_SET.compareAndSet(false, true)) {
                return;
            }

            resolutionNs = Math.max(resolutionNs, 0);

            try {
                int res = (int) Math.min(Integer.MAX_VALUE, resolutionNs) / 100;
                IntByReference current = new IntByReference();
                INSTANCE.NtSetTimerResolution(res, true, current);

                System.out.printf("Windows: Requested resolution: %d Applied Resolution: %d\n",
                        resolutionNs, current.getValue() * 100L);

                windowsResolution100ns = current.getValue();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        IntByReference cur = new IntByReference();
                        INSTANCE.NtSetTimerResolution(windowsResolution100ns, false, cur);
                    } catch (Throwable ignored) {
                    }
                }, "win-timer-release"));
            } catch (Throwable t) {
                System.err.println("Failed to set Windows timer resolution: " + t);
                WIN_RES_SET.set(false);
            }
        }

        int NtSetTimerResolution(int desired, boolean set, IntByReference current);
    }
}
