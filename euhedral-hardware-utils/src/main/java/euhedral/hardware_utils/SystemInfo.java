package euhedral.hardware_utils;

import euhedral.hardware_utils.common.OSName;
import euhedral.hardware_utils.common.SystemSnapshotProvider;
import euhedral.hardware_utils.common.SystemUtilization.SystemSnapshot;
import euhedral.hardware_utils.linux.CgroupV2Resources;
import euhedral.hardware_utils.linux.LinuxSystemLayout;
import euhedral.hardware_utils.macOS.OSXResources;
import euhedral.hardware_utils.windows.WindowsResources;
import euhedral.hardware_utils.windows.WindowsSystemLayout;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import java.util.BitSet;
import java.util.StringJoiner;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SystemInfo {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemInfo.class);

    public static final long DEFAULT_L1 = 32L * 1024L;
    public static final long DEFAULT_L2 = 256L * 1024L;
    public static final long DEFAULT_L3 = 4L * 1024L * 1024L;

    public static final SystemSnapshotProvider SNAPSHOTTER;

    public static final int CACHE_LINE_SIZE_BYTES;

    public static final int CPU_COUNT;
    public static final int CORE_COUNT;
    public static final int SOCKET_COUNT;
    public static final int MAX_CORE_ID;
    public static final int MAX_SOCKET_ID;

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

        LOGGER.debug("\n{}", asString());
        if(OSName.CURRENT_OS != OSName.UNSUPPORTED) {
            CACHE_LINE_SIZE_BYTES = CPU_CACHE.get(0).cacheLineBytes;
        } else {
            CACHE_LINE_SIZE_BYTES = 64;
        }
    }

    public static int getCacheLineBytes() {
        return CACHE_LINE_SIZE_BYTES;
    }

    public static int getCpuCount() {
        return CPU_COUNT;
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
        StringJoiner sj = new StringJoiner(",");

        long[] bits = set.toLongArray();
        for (int i = bits.length - 1; i >= 0; i--) {
            long chunk = bits[i];
            int upper = (int) (chunk >>> 32);
            int lower = (int) ((chunk << 32) >>> 32);

            if (upper != 0 && i == 0) {
                sj.add(Integer.toHexString(upper));
            }
            sj.add(Integer.toHexString(lower));
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

    public static @Nullable SocketInfo getSocketInfo(int socket) {
        return SOCKET_INFO.get(socket);
    }

    public static @Nullable CoreInfo getCoreInfo(int core) {
        return CORE_INFO.get(core);
    }

    public static @Nullable CpuInfo getCpuInfo(int cpu) {
        return CPU_INFO.get(cpu);
    }

    public static @Nullable CpuCacheLayout getCacheLayout(int cpu) {
        return CPU_CACHE.get(cpu);
    }

    private SystemInfo() {

    }

    public record CpuInfo(int cpu, int core, int socket) {

        @Override
        public String toString() {
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
            return "\u001b[1;48;2;75;76;155m" + base + "\u001b[0m";
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
            String base =  CpuCacheLayout.class.getSimpleName()
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
