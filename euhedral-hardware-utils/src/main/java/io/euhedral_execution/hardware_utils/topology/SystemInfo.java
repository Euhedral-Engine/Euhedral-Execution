package io.euhedral_execution.hardware_utils.topology;

import io.euhedral_execution.hardware_utils.linux.LinuxSystemLayout;
import io.euhedral_execution.hardware_utils.macos.MacosSystemLayout;
import io.euhedral_execution.hardware_utils.monitor.SystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.topology.internal.MaskCodec;
import io.euhedral_execution.hardware_utils.topology.internal.TopologyBootstrap;
import io.euhedral_execution.hardware_utils.topology.internal.TopologyModel;
import io.euhedral_execution.hardware_utils.topology.internal.TopologyQueries;
import io.euhedral_execution.hardware_utils.util.OSName;
import io.euhedral_execution.hardware_utils.util.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.util.internal.Constants;
import io.euhedral_execution.hardware_utils.windows.WindowsSystemLayout;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
public final class SystemInfo {

    public static final long DEFAULT_L1 = 32L * 1024L;
    public static final long DEFAULT_L2 = 256L * 1024L;
    public static final long DEFAULT_L3 = 4L * 1024L * 1024L;

    public static final int CACHE_LINE_SIZE_BYTES;
    public static final int CPU_COUNT;
    public static final int CORE_COUNT;
    public static final int SOCKET_COUNT;
    public static final int MAX_CORE_ID;
    public static final int MAX_SOCKET_ID;

    public static final @Nullable SystemSnapshotProvider SNAPSHOTTER;

    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(SystemInfo.class));
    private static final UnmodifiableBitSet CPU_SET;
    private static final UnmodifiableBitSet CORE_SET;
    private static final UnmodifiableBitSet P_CORE_SET;
    private static final UnmodifiableBitSet E_CORE_SET;
    private static final UnmodifiableBitSet P_CPU_SET;
    private static final UnmodifiableBitSet E_CPU_SET;

    private static final Map<Integer, CpuCacheLayout> CPU_CACHE;
    private static final Map<Integer, CpuInfo> CPU_INFO;
    private static final Map<Integer, CoreInfo> CORE_INFO;
    private static final Map<Integer, SocketInfo> SOCKET_INFO;
    private static final TopologyModel TOPOLOGY_MODEL;

    @Getter
    private static final boolean X86;

    static {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        X86 = !(arch.startsWith("aarch64") || arch.contains("arm64"));

        TOPOLOGY_MODEL = selectTopology();
        CPU_CACHE = TOPOLOGY_MODEL.cacheLayout();
        CPU_INFO = TOPOLOGY_MODEL.cpuInfo();
        CORE_INFO = TOPOLOGY_MODEL.coreInfo();
        SOCKET_INFO = TOPOLOGY_MODEL.socketInfo();
        CPU_COUNT = TOPOLOGY_MODEL.cpuCount();
        CORE_COUNT = TOPOLOGY_MODEL.coreCount();
        SOCKET_COUNT = TOPOLOGY_MODEL.socketCount();
        MAX_CORE_ID = TOPOLOGY_MODEL.maxCoreId();
        MAX_SOCKET_ID = TOPOLOGY_MODEL.maxSocketId();
        CACHE_LINE_SIZE_BYTES = TOPOLOGY_MODEL.cacheLineBytes();
        CPU_SET = TOPOLOGY_MODEL.cpuSet();
        P_CORE_SET = TOPOLOGY_MODEL.pCoreSet();
        E_CORE_SET = TOPOLOGY_MODEL.eCoreSet();
        P_CPU_SET = TOPOLOGY_MODEL.pCpuSet();
        E_CPU_SET = TOPOLOGY_MODEL.eCpuSet();
        SNAPSHOTTER = TopologyBootstrap.resources(LOGGER);

        BitSet cores = new BitSet();
        cores.or(P_CORE_SET);
        cores.or(E_CORE_SET);
        CORE_SET = UnmodifiableBitSet.wrap(cores);

        if (LOGGER.isDebugEnabled()) {
            try {
                LOGGER.debug("\n{}", asString());
            } catch (RuntimeException e) {
                LOGGER.debug("topology dump failed", e);
            }
        }
    }

    private SystemInfo() {}

    public static int getCacheLineBytes() {
        return CACHE_LINE_SIZE_BYTES;
    }

    public static int getCpuCount() {
        return CPU_COUNT;
    }

    public static int getCoreCount() {
        return CORE_COUNT;
    }

    public static int getMaxCoreId() {
        return MAX_CORE_ID;
    }

    public static int getMaxSocketId() {
        return MAX_SOCKET_ID;
    }

    public static @Nullable SystemSnapshot getSystemSnapshot() {
        return SNAPSHOTTER.getSnapshot();
    }

    public static int[] getSystemCpus() {
        return TOPOLOGY_MODEL.activeLogicalIds();
    }

    public static @NonNull BitSet fromHexMask(@NonNull String mask) {
        return MaskCodec.parse(mask);
    }

    public static @NonNull String toHexMask(@NonNull BitSet set) {
        return MaskCodec.format(set);
    }

    public static String asString() {
        String bold = "\u001B[1m";
        String reset = "\u001B[0m";
        String lineBreak = "-".repeat(80);

        StringJoiner sj = new StringJoiner("\n");
        sj.add(lineBreak);
        sj.add(bold + SystemInfo.class.getSimpleName() + reset);
        sj.add(lineBreak);

        for (int i = 0; i < MAX_SOCKET_ID + 1; i++) {
            SocketInfo info = getSocketInfo(i);
            if (info != null) {
                sj.add(info.toString());
            }
        }
        sj.add(lineBreak);
        for (int i = 0; i < MAX_CORE_ID + 1; i++) {
            CoreInfo info = getCoreInfo(i);
            if (info != null) {
                sj.add(info.pCore() ? pCoreColor(info.toString()) : info.toString());
            }
        }
        sj.add(lineBreak);
        for (int i = 0; i < CPU_COUNT; i++) {
            CpuInfo info = getCpuInfo(i);
            if (info != null) {
                sj.add(isPCore(info.core()) ? pCoreColor(info.toString()) : info.toString());
            }
        }
        sj.add(lineBreak);
        for (int i = 0; i < CPU_COUNT; i++) {
            CpuCacheLayout layout = getCacheLayout(i);
            if (layout == null) {
                continue;
            }
            CpuInfo cpu = getCpuInfo(layout.cpu());
            boolean pCore = cpu != null && isPCore(cpu.core());
            sj.add(pCore ? pCoreColor(layout.toString()) : layout.toString());
        }
        sj.add(lineBreak);

        return sj.toString();
    }

    private static boolean isPCore(int core) {
        CoreInfo info = CORE_INFO.get(core);
        return info != null && info.pCore();
    }

    private static String pCoreColor(String line) {
        return "\u001b[1;35m" + line + "\u001b[0m";
    }

    public static @Nullable SocketInfo getSocketInfo(int socket) {
        return SOCKET_INFO.get(socket);
    }

    public static @Nullable CoreInfo getCoreInfo(int core) {
        return CORE_INFO.get(core);
    }

    public static @Nullable CpuInfo getCpuInfo(int cpu) {
        return CPU_INFO.get(cpu);
    }

    /// Returns whether {@code cpu} is an active logical CPU, equivalent to {@code getCpuInfo(cpu) != null}.
    /// Never throws; negative and out-of-range ids are simply not active.
    public static boolean isActiveCpu(int cpu) {
        return TopologyQueries.isActiveCpu(TOPOLOGY_MODEL, cpu);
    }

    /// Returns whether {@code core} is an active core, equivalent to {@code getCoreInfo(core) != null}.
    public static boolean isActiveCore(int core) {
        return TopologyQueries.isActiveCore(TOPOLOGY_MODEL, core);
    }

    /// Returns whether {@code socket} is an active socket, equivalent to {@code getSocketInfo(socket) != null}.
    public static boolean isActiveSocket(int socket) {
        return TopologyQueries.isActiveSocket(TOPOLOGY_MODEL, socket);
    }

    /// Strict form of {@link #getCpuInfo(int)}.
    ///
    /// @throws IllegalArgumentException if {@code cpu} is not active; the message names the id and the active set
    public static CpuInfo getCpuInfoOrThrow(int cpu) {
        return TopologyQueries.getCpuInfoOrThrow(TOPOLOGY_MODEL, cpu);
    }

    /// Strict form of {@link #getCoreInfo(int)}.
    ///
    /// @throws IllegalArgumentException if {@code core} is not active
    public static CoreInfo getCoreInfoOrThrow(int core) {
        return TopologyQueries.getCoreInfoOrThrow(TOPOLOGY_MODEL, core);
    }

    /// Strict form of {@link #getSocketInfo(int)}.
    ///
    /// @throws IllegalArgumentException if {@code socket} is not active
    public static SocketInfo getSocketInfoOrThrow(int socket) {
        return TopologyQueries.getSocketInfoOrThrow(TOPOLOGY_MODEL, socket);
    }

    /// Strict form of {@link #getCacheLayout(int)}.
    ///
    /// @throws IllegalArgumentException if {@code cpu} is not active
    public static CpuCacheLayout getCacheLayoutOrThrow(int cpu) {
        return TopologyQueries.getCacheLayoutOrThrow(TOPOLOGY_MODEL, cpu);
    }

    public static @Nullable CpuCacheLayout getCacheLayout(int cpu) {
        return CPU_CACHE.get(cpu);
    }

    public static @NonNull UnmodifiableBitSet getCpuSet() {
        return CPU_SET;
    }

    public static long socketL3Cache(int socket) {
        long sum = 0;
        SocketInfo info = getSocketInfo(socket);
        if (info == null) {
            return sum;
        }

        BitSet cpus = info.getCpuSet();
        Set<String> visited = new HashSet<>();
        for (int i = cpus.nextSetBit(0); i >= 0; i = cpus.nextSetBit(i + 1)) {
            CpuCacheLayout layout = getCacheLayout(i);
            if (visited.contains(layout.maskL3)) {
                continue;
            }
            sum += layout.bytesL3;
            visited.add(layout.maskL3);
        }

        return sum;
    }

    public static @NonNull UnmodifiableBitSet getCoreSet() {
        return CORE_SET;
    }

    public static @NonNull UnmodifiableBitSet getPCoreSet() {
        return P_CORE_SET;
    }

    public static @NonNull UnmodifiableBitSet getECoreSet() {
        return E_CORE_SET;
    }

    public static @NonNull UnmodifiableBitSet getPCpuSet() {
        return P_CPU_SET;
    }

    public static @NonNull UnmodifiableBitSet getECpuSet() {
        return E_CPU_SET;
    }

    static TopologyModel topologyModel() {
        return TOPOLOGY_MODEL;
    }

    private static TopologyModel selectTopology() {
        try {
            if (OSName.isLinux()) {
                return TopologyBootstrap.extract(
                        LinuxSystemLayout.INSTANCE.getCacheLayout(),
                        LinuxSystemLayout.INSTANCE.getCpuInfoMap(),
                        LinuxSystemLayout.INSTANCE.getCoreInfoMap(),
                        LinuxSystemLayout.INSTANCE.getSocketInfoMap());
            }
            if (OSName.isWindows()) {
                return TopologyBootstrap.extract(
                        WindowsSystemLayout.INSTANCE.getCacheLayout(),
                        WindowsSystemLayout.INSTANCE.getCpuInfoMap(),
                        WindowsSystemLayout.INSTANCE.getCoreInfoMap(),
                        WindowsSystemLayout.INSTANCE.getSocketInfoMap());
            }
            if (OSName.isMacOS()) {
                return TopologyBootstrap.extract(
                        MacosSystemLayout.INSTANCE.getCacheLayout(),
                        MacosSystemLayout.INSTANCE.getCpuInfoMap(),
                        MacosSystemLayout.INSTANCE.getCoreInfoMap(),
                        MacosSystemLayout.INSTANCE.getSocketInfoMap());
            }
            LOGGER.error("Unsupported OS; using common fallback topology.");
        } catch (Exception | LinkageError failure) {
            LOGGER.error("Failed to initialize platform topology; using common fallback.", failure);
        }
        return TopologyBootstrap.fallback(Runtime.getRuntime().availableProcessors());
    }

    public record CpuInfo(int cpu, int core, int socket) {

        @Override
        public @NonNull String toString() {
            return CpuInfo.class.getSimpleName() + ": ["
                    + "cpu = " + cpu + ", "
                    + "core = " + core + ", "
                    + "socket = " + socket + "]";
        }
    }

    public record CoreInfo(String cpuHexMask, boolean pCore, int core, int socket) {

        public @NonNull BitSet getCpuSet() {
            return fromHexMask(cpuHexMask);
        }

        @Override
        public @NonNull String toString() {
            return CoreInfo.class.getSimpleName() + ": ["
                    + "cpuHexMask = \"" + cpuHexMask + "\", "
                    + "pCore = " + pCore + ", "
                    + "core = " + core + ", "
                    + "socket = " + socket + "]";
        }
    }

    public record SocketInfo(String cpuHexMask, String coreHexMask, int socket) {

        public @NonNull BitSet getCpuSet() {
            return fromHexMask(cpuHexMask);
        }

        public @NonNull BitSet getCoreSet() {
            return fromHexMask(coreHexMask);
        }

        @Override
        public @NonNull String toString() {
            return SocketInfo.class.getSimpleName() + ": ["
                    + "cpuHexMask = \"" + cpuHexMask + "\", "
                    + "coreHexMask = \"" + coreHexMask + "\", "
                    + "socket = " + socket + "]";
        }
    }

    /// Shares of size 1 means only this cpu uses the level of cache.
    public record CpuCacheLayout(
            int cpu,
            long bytesL1,
            long bytesL2,
            long bytesL3,
            int sharesL1,
            int sharesL2,
            int sharesL3,
            String maskL1,
            String maskL2,
            String maskL3,
            int cacheLineBytes) {

        public @NonNull BitSet getL1Mask() {
            return fromHexMask(maskL1);
        }

        public @NonNull BitSet getL2Mask() {
            return fromHexMask(maskL2);
        }

        public @NonNull BitSet getL3Mask() {
            return fromHexMask(maskL3);
        }

        @Override
        public @NonNull String toString() {
            return CpuCacheLayout.class.getSimpleName()
                    + ": [cpu = " + cpu + ", "
                    + "bytesL1 = " + formatBytes(bytesL1) + ", "
                    + "bytesL2 = " + formatBytes(bytesL2) + ", "
                    + "bytesL3 = " + formatBytes(bytesL3) + ", "
                    + "sharesL1 = " + sharesL1 + ", "
                    + "sharesL2 = " + sharesL2 + ", "
                    + "sharesL3 = " + sharesL3 + ", "
                    + "maskL1 = \"" + maskL1 + "\", "
                    + "maskL2 = \"" + maskL2 + "\", "
                    + "maskL3 = \"" + maskL3 + "\", "
                    + "cacheLineBytes = " + cacheLineBytes + "]";
        }

        private String formatBytes(long bytes) {
            bytes /= 1024;
            if (bytes < 1024) {
                return bytes + "KB";
            }
            return bytes / 1024 + "MB";
        }
    }
}
