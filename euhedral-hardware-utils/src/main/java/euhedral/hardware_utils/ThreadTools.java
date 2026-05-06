package euhedral.hardware_utils;

import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.hardware_utils.common.OSName;
import euhedral.hardware_utils.common.ThreadPinner;
import euhedral.hardware_utils.linux.LinuxAffinity;
import euhedral.hardware_utils.macOS.OSXAffinity;
import euhedral.hardware_utils.windows.WindowsAffinity;
import java.util.BitSet;

public class ThreadTools {
    private static final ThreadPinner PINNER;

    static {
        if(OSName.isLinux()) {
            PINNER = LinuxAffinity.INSTANCE;
        } else if(OSName.isWindows()) {
            PINNER = WindowsAffinity.INSTANCE;
        } else if(OSName.isMacOS()) {
            PINNER = OSXAffinity.INSTANCE;
        } else {
            PINNER = null;
        }
    }

    /// Gets the cpu of the calling thread.
    ///
    /// @return logical cpu ID
    public static int getCpu() {
        return PINNER.getCpu();
    }

    /// Gets the CpuInfo for the cpu the calling thread is running on.
    ///
    /// @return cpu, core, socket
    public static CpuInfo getOrigin() {
        int cpu = PINNER.getCpu();
        return SystemInfo.getCpuInfo(cpu);
    }

    /// Sets the calling thread's affinity to the cpu it is running on.
    ///
    /// @return success
    public static boolean setAffinity() {
        return setAffinity(PINNER.getCpu());
    }

    /// Sets the calling thread's affinity to the specified cpu.
    ///
    /// @param cpu logical cpu id
    /// @return success
    public static boolean setAffinity(int cpu) {
        long[] masks = new long[cpu / 64 + ((cpu & 63) > 0 ? 1 : 0)];

        masks[0] = 1L << (cpu & 63);
        return setAffinity(masks);
    }

    /// Sets the calling thread's affinity to the specified list of cpus.
    ///
    /// @param cpus list of logical cpus
    /// ```java
    /// int[] cpus = {0, 1, 5, 8}
    /// ```
    /// @return success
    public static boolean setAffinity(int[] cpus) {
        BitSet set = new BitSet(cpus.length);
        for(int i : cpus) {
            set.set(i);
        }
        return setAffinity(set.toLongArray());
    }

    /// Sets the calling thread's affinity to the cpus set in the BitSet. The indexes must
    /// correspond to the logical ID of the cpu.
    ///
    /// @param cpus set of cpus
    /// @return success
    public static boolean setAffinity(BitSet cpus) {
        return setAffinity(cpus.toLongArray());
    }

    /// Sets the calling thread's affinity using the set of masks with set bits corresponding
    /// to the logical cpu IDs. The mask array must be ordered using little-endian
    ///
    /// @param cpuMasks little-endian ordered masks
    /// @return success
    public static boolean setAffinity(long[] cpuMasks) {
        return PINNER.setAffinity(cpuMasks);
    }

    /// Sets the resolution of timers for the calling thread. e.g LockSupport.parkNanos(50)
    ///
    /// @param nanos positive nanoseconds
    /// @return success
    public static boolean setTimerResolution(long nanos) {
        return PINNER.setTimerResolution(nanos);
    }
}
