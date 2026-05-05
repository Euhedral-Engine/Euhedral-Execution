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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SystemInfo {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemInfo.class);

    public static final long DEFAULT_L1 = 32L * 1024L;
    public static final long DEFAULT_L2 = 256L * 1024L;
    public static final long DEFAULT_L3 = 4L * 1024L * 1024L;

    public static final SystemSnapshotProvider SNAPSHOTTER;

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

    public static @NonNull CpuCacheLayout getCacheLayout(int cpu) {
        CpuCacheLayout layout = CPU_CACHE.get(cpu);
        if (layout == null) {
            return new CpuCacheLayout(cpu, DEFAULT_L1, DEFAULT_L2, DEFAULT_L3, 1, 1,
                    Runtime.getRuntime().availableProcessors(), "", "", "", 64);
        }
        return layout;
    }

    public static CpuInfo getCpuInfo(int cpu) {
        return CPU_INFO.get(cpu);
    }

    public static CoreInfo getCoreInfo(int core) {
        return CORE_INFO.get(core);
    }

    public static SocketInfo getSocketInfo(int socket) {
        return SOCKET_INFO.get(socket);
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

            sj.add(Integer.toHexString(upper));
            sj.add(Integer.toHexString(lower));
        }
        return sj.toString();
    }

    private SystemInfo() {

    }

    public record CpuInfo(int cpu, int core, int socket) {


    }

    public record CoreInfo(String cpuHexMask, boolean pCore, int core, int socket) {

        public @NonNull BitSet getCpuSet() {
            return fromHexMask(cpuHexMask);
        }

    }

    public record SocketInfo(String cpuHexMask, String coreHexMask, int socket) {

        public @NonNull BitSet getCpuSet() {
            return fromHexMask(cpuHexMask);
        }

        public @NonNull BitSet getCoreSet() {
            return fromHexMask(coreHexMask);
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
    }
}
