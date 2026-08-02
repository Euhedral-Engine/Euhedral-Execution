package io.euhedral_execution.hardware_utils;

import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.internal.AffinityController;
import io.euhedral_execution.hardware_utils.internal.AffinityMasks;
import io.euhedral_execution.hardware_utils.internal.AffinityProvider;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.linux.LinuxAffinity;
import io.euhedral_execution.hardware_utils.osx.OSXAffinity;
import io.euhedral_execution.hardware_utils.windows.WindowsAffinity;
import java.util.BitSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
public final class ThreadTools {
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(ThreadTools.class));
    private static final AffinityController CONTROLLER;

    public static final UnmodifiableBitSet BASE_MASK;

    static {
        BitSet supported = SystemInfo.getCpuSet();
        int span = Math.max(1, SystemInfo.getCpuCount());
        AffinityProvider provider = selectProvider();
        CONTROLLER = new AffinityController(provider, supported, span, LOGGER);
        BASE_MASK = CONTROLLER.baseMask();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Base Affinity Mask: {} 0x{}", BASE_MASK, SystemInfo.toHexMask(BASE_MASK));
        }
    }

    /// Reports the affinity behavior the complete common path can apply and undo.
    ///
    /// `LOCALITY_HINT` means scheduler preference only. `EXACT` means a mask can be captured,
    /// applied completely, and restored. `UNSUPPORTED` makes affinity requests harmless failures.
    public static AffinityCapability getAffinityCapability() {
        return CONTROLLER.capability();
    }

    /// Gets the cpu of the calling thread.
    ///
    /// @return logical cpu ID, or -1 when unavailable
    public static int getCpu() {
        return CONTROLLER.currentCpu();
    }

    /// Gets the calling thread's CpuInfo for the cpu it is running on.
    ///
    /// @return cpu, core, socket, or null when unavailable
    public static @Nullable CpuInfo getCpuInfo() {
        int cpu = CONTROLLER.currentCpu();
        return cpu < 0 ? null : SystemInfo.getCpuInfo(cpu);
    }

    public static boolean setAffinity() {
        return setAffinity(CONTROLLER.currentCpu());
    }

    public static boolean setAffinity(int cpu) {
        if (cpu < 0 || cpu >= SystemInfo.getCpuCount() || !SystemInfo.getCpuSet().get(cpu)) {
            return false;
        }
        long[] masks = new long[(cpu >>> 6) + 1];
        masks[cpu >>> 6] = 1L << (cpu & 63);
        return CONTROLLER.setAffinity(masks);
    }

    public static boolean setAffinity(int[] cpus) {
        if (cpus == null || cpus.length == 0 || cpus.length > AffinityMasks.MAX_BITS) {
            return false;
        }
        int[] owned = cpus.clone();
        int highest = -1;
        for (int cpu : owned) {
            if (cpu < 0 || cpu >= SystemInfo.getCpuCount() || !SystemInfo.getCpuSet().get(cpu)) {
                return false;
            }
            highest = Math.max(highest, cpu);
        }
        long[] masks = new long[(highest >>> 6) + 1];
        for (int cpu : owned) {
            masks[cpu >>> 6] |= 1L << (cpu & 63);
        }
        return CONTROLLER.setAffinity(masks);
    }

    public static boolean setAffinity(BitSet cpus) {
        if (cpus == null || cpus.isEmpty() || cpus.length() > AffinityMasks.MAX_BITS) {
            return false;
        }
        BitSet owned = (BitSet) cpus.clone();
        if (owned.length() > AffinityMasks.MAX_BITS) {
            return false;
        }
        return CONTROLLER.setAffinity(owned.toLongArray());
    }

    public static boolean setAffinity(long[] cpuMasks) {
        return CONTROLLER.setAffinity(cpuMasks);
    }

    public static void releaseAffinity() {
        CONTROLLER.releaseAffinity();
    }

    public static boolean setTimerResolution(long nanos) {
        return CONTROLLER.setTimerResolution(nanos);
    }

    /// Associates a managed task with a logical CPU without claiming physical placement.
    ///
    /// The returned binding closes on its creator thread in last-in, first-out order.
    static ManagedCpuBinding bindManagedCpu(int logicalCpu) {
        AffinityController.ManagedOwner owner = CONTROLLER.bindManagedCpu(logicalCpu);
        return owner::close;
    }

    private static AffinityProvider selectProvider() {
        try {
            if (OSName.isLinux()) {
                return LinuxAffinity.INSTANCE;
            }
            if (OSName.isWindows()) {
                return WindowsAffinity.INSTANCE;
            }
            if (OSName.isMacOS()) {
                return OSXAffinity.INSTANCE;
            }
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.debug("Affinity provider selection failed", failure);
        }
        return null;
    }

    /// Scoped managed logical-CPU association consumed by the pinned executor in P3-B.
    interface ManagedCpuBinding extends AutoCloseable {

        @Override
        void close();
    }

    private ThreadTools() {
    }
}
