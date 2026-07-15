package io.euhedral_execution.hardware_utils;

import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.linux.CgroupV2Resources;
import io.euhedral_execution.hardware_utils.linux.LinuxSystemLayout;
import io.euhedral_execution.hardware_utils.osx.OSXResources;
import io.euhedral_execution.hardware_utils.windows.WindowsResources;
import io.euhedral_execution.hardware_utils.windows.WindowsSystemLayout;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
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

    public static final SystemSnapshotProvider SNAPSHOTTER;

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemInfo.class);
    private static final UnmodifiableBitSet CPU_SET;
    private static final UnmodifiableBitSet P_CORE_SET;
    private static final UnmodifiableBitSet E_CORE_SET;
    private static final UnmodifiableBitSet P_CPU_SET;
    private static final UnmodifiableBitSet E_CPU_SET;

    private static final Int2ObjectArrayMap<CpuCacheLayout> CPU_CACHE;
    private static final Int2ObjectArrayMap<CpuInfo> CPU_INFO;
    private static final Int2ObjectArrayMap<CoreInfo> CORE_INFO;
    private static final Int2ObjectArrayMap<SocketInfo> SOCKET_INFO;

    @Getter
    private static final boolean X86;

    static {
        String arch = System.getProperty("os.arch").toLowerCase();
        X86 = !(arch.startsWith("aarch64") || arch.contains("arm64"));

        if (OSName.isLinux()) {
            CPU_CACHE = new Int2ObjectArrayMap<>(LinuxSystemLayout.INSTANCE.getCacheLayout());
            CPU_INFO = new Int2ObjectArrayMap<>(LinuxSystemLayout.INSTANCE.getCpuInfoMap());
            CORE_INFO = new Int2ObjectArrayMap<>(LinuxSystemLayout.INSTANCE.getCoreInfoMap());
            SOCKET_INFO = new Int2ObjectArrayMap<>(LinuxSystemLayout.INSTANCE.getSocketInfoMap());

            CPU_COUNT = CPU_INFO.size();
            CORE_COUNT = CORE_INFO.size();
            SOCKET_COUNT = SOCKET_INFO.size();
            SNAPSHOTTER = new CgroupV2Resources();
        } else if (OSName.isMacOS()) {
            CPU_CACHE = new Int2ObjectArrayMap<>();
            CPU_INFO = new Int2ObjectArrayMap<>();
            CORE_INFO = new Int2ObjectArrayMap<>();
            SOCKET_INFO = new Int2ObjectArrayMap<>();

            CPU_COUNT = Runtime.getRuntime().availableProcessors();
            CORE_COUNT = CPU_COUNT;
            SOCKET_COUNT = 1;
            SNAPSHOTTER = OSXResources.INSTANCE;
        } else if (OSName.isWindows()) {
            CPU_CACHE = new Int2ObjectArrayMap<>(WindowsSystemLayout.INSTANCE.getCacheLayout());
            CPU_INFO = new Int2ObjectArrayMap<>(WindowsSystemLayout.INSTANCE.getCpuInfoMap());
            CORE_INFO = new Int2ObjectArrayMap<>(WindowsSystemLayout.INSTANCE.getCoreInfoMap());
            SOCKET_INFO = new Int2ObjectArrayMap<>(WindowsSystemLayout.INSTANCE.getSocketInfoMap());

            CPU_COUNT = CPU_INFO.size();
            CORE_COUNT = CORE_INFO.size();
            SOCKET_COUNT = SOCKET_INFO.size();
            SNAPSHOTTER = WindowsResources.INSTANCE;
        } else {
            LOGGER.error("Unsupported OS. Defaulting to null and empty.");

            CPU_CACHE = new Int2ObjectArrayMap<>();
            CPU_INFO = new Int2ObjectArrayMap<>();
            CORE_INFO = new Int2ObjectArrayMap<>();
            SOCKET_INFO = new Int2ObjectArrayMap<>();

            CPU_COUNT = Runtime.getRuntime().availableProcessors();
            CORE_COUNT = CPU_COUNT;
            SOCKET_COUNT = 1;
            SNAPSHOTTER = null;
        }

        int maxCore = 0;
        int maxSocket = 0;
        for (CpuInfo info : CPU_INFO.values()) {
            maxCore = Math.max(maxCore, info.core);
            maxSocket = Math.max(maxSocket, info.socket);
        }
        MAX_CORE_ID = maxCore;
        MAX_SOCKET_ID = maxSocket;

        BitSet cpus = new BitSet(CPU_INFO.size());
        BitSet pCores = new BitSet(MAX_CORE_ID);
        BitSet eCores = new BitSet(MAX_CORE_ID);
        BitSet pCpus = new BitSet(CPU_INFO.size());
        BitSet eCpus = new BitSet(CPU_INFO.size());
        if (OSName.CURRENT_OS != OSName.UNSUPPORTED) {
            CACHE_LINE_SIZE_BYTES = CPU_CACHE.get(0).cacheLineBytes;
            for (int c : CPU_INFO.keySet()) {
                cpus.set(c);
                int core = CPU_INFO.get(c).core;
                CoreInfo coreInfo = CORE_INFO.get(core);
                pCores.set(core, coreInfo.pCore);
                pCpus.set(c, coreInfo.pCore);
                eCores.set(core, !coreInfo.pCore);
                eCpus.set(c, !coreInfo.pCore);
            }
        } else {
            CACHE_LINE_SIZE_BYTES = 64;
            cpus.set(0, Runtime.getRuntime().availableProcessors());
        }
        CPU_SET = UnmodifiableBitSet.wrap(cpus);
        P_CORE_SET = UnmodifiableBitSet.wrap(pCores);
        E_CORE_SET = UnmodifiableBitSet.wrap(eCores);
        P_CPU_SET = UnmodifiableBitSet.wrap(pCpus);
        E_CPU_SET = UnmodifiableBitSet.wrap(eCpus);

        String debugOut = asString();
        LOGGER.debug("\n{}", debugOut);
    }

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

    public static SystemSnapshot getSystemSnapshot() {
        return SNAPSHOTTER.getSnapshot();
    }

    public static int[] getSystemCpus() {
        return CPU_CACHE.keySet().toIntArray();
    }

    public static @NonNull BitSet fromHexMask(@NonNull String mask) {
        String[] chunks = mask.split(",");

        int bit = 0;
        BitSet set = new BitSet(32 * chunks.length);
        for (int i = chunks.length - 1; i >= 0; i--) {
            long subMask = Long.parseUnsignedLong(chunks[i].replace("0x", "").trim(), 16);

            int shifts = 0;
            while (subMask > 0) {
                int cpu = Long.numberOfTrailingZeros(subMask) + 1;
                shifts += cpu;
                bit += cpu;
                set.set(bit - 1);
                subMask >>>= cpu;
            }
            bit += 32 - shifts;
        }
        return set;
    }

    public static @NonNull String toHexMask(@NonNull BitSet set) {
        if (set.isEmpty()) {
            return "0";
        }

        StringJoiner sj = new StringJoiner(",");
        long[] bits = set.toLongArray();
        boolean headWritten = false;

        for (int i = bits.length - 1; i >= 0; i--) {
            long chunk = bits[i];
            int upper = (int) (chunk >>> 32);
            int lower = (int) chunk;

            if (headWritten) {
                sj.add(String.format("%08x", upper));
            } else if (upper != 0) {
                sj.add(Integer.toHexString(upper));
                headWritten = true;
            }

            if (headWritten) {
                sj.add(String.format("%08x", lower));
            } else if (lower != 0 || i == 0) {
                sj.add(Integer.toHexString(lower));
                headWritten = true;
            }
        }
        return sj.toString();
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
                sj.add(info.toString());
            }
        }
        sj.add(lineBreak);
        for (int i = 0; i < CPU_COUNT; i++) {
            CpuInfo info = getCpuInfo(i);
            if (info != null) {
                sj.add(info.toString());
            }
        }
        sj.add(lineBreak);
        for (int i = 0; i < CPU_COUNT; i++) {
            CpuCacheLayout layout = getCacheLayout(i);
            if (layout != null) {
                sj.add(layout.toString());
            }
        }
        sj.add(lineBreak);

        return sj.toString();
    }

    public static SocketInfo getSocketInfo(int socket) {
        return SOCKET_INFO.get(socket);
    }

    public static CoreInfo getCoreInfo(int core) {
        return CORE_INFO.get(core);
    }

    public static CpuInfo getCpuInfo(int cpu) {
        return CPU_INFO.get(cpu);
    }

    public static CpuCacheLayout getCacheLayout(int cpu) {
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

    private SystemInfo() {

    }

    public record CpuInfo(int cpu, int core, int socket) {

        @Override
        public @NonNull String toString() {
            String base = CpuInfo.class.getSimpleName() + ": ["
                    + "cpu = " + cpu + ", "
                    + "core = " + core + ", "
                    + "socket = " + socket + "]";
            CoreInfo pCore = CORE_INFO.get(core);
            return pCore != null && pCore.pCore ? pCore.color(base) : base;
        }
    }

    public record CoreInfo(String cpuHexMask, boolean pCore, int core, int socket) {

        public @NonNull BitSet getCpuSet() {
            return fromHexMask(cpuHexMask);
        }

        @Override
        public @NonNull String toString() {
            String base = CoreInfo.class.getSimpleName() + ": ["
                    + "cpuHexMask = \"" + cpuHexMask + "\", "
                    + "pCore = " + pCore + ", "
                    + "core = " + core + ", "
                    + "socket = " + socket + "]";
            return pCore ? color(base) : base;
        }

        private String color(String base) {
            return "\u001b[1;35m" + base + "\u001b[0m";
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
    public record CpuCacheLayout(int cpu, long bytesL1, long bytesL2, long bytesL3, int sharesL1,
                                 int sharesL2, int sharesL3, String maskL1, String maskL2,
                                 String maskL3, int cacheLineBytes) {

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
            String base = CpuCacheLayout.class.getSimpleName()
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
            CoreInfo pCore = CORE_INFO.get(CPU_INFO.get(cpu).core);
            return pCore != null && pCore.pCore ? pCore.color(base) : base;
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
