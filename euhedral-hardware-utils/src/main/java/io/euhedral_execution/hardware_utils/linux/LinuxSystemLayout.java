package io.euhedral_execution.hardware_utils.linux;

import static io.euhedral_execution.hardware_utils.SystemInfo.DEFAULT_L1;
import static io.euhedral_execution.hardware_utils.SystemInfo.DEFAULT_L2;
import static io.euhedral_execution.hardware_utils.SystemInfo.DEFAULT_L3;

import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.Constants;
import it.unimi.dsi.fastutil.ints.Int2BooleanArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LinuxSystemLayout {

    public static final LinuxSystemLayout INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(LinuxSystemLayout.class));

    static {
        if (OSName.isLinux()) {
            INSTANCE = new LinuxSystemLayout();
        } else {
            INSTANCE = null;
        }
    }

    private final Int2ObjectArrayMap<CpuCacheLayout> cpuCache = new Int2ObjectArrayMap<>();
    private final Int2ObjectArrayMap<CpuInfo> cpuInfo = new Int2ObjectArrayMap<>();
    private final Int2ObjectArrayMap<CoreInfo> coreInfo = new Int2ObjectArrayMap<>();
    private final Int2ObjectArrayMap<SocketInfo> socketInfo = new Int2ObjectArrayMap<>();

    private Int2ObjectArrayMap<Ranking> cpuRankings = new Int2ObjectArrayMap<>();

    private LinuxSystemLayout() {
        init();
        cpuRankings = null;
    }

    private void init() {
        try (var cpuDirs = Files.list(LinuxPaths.CPU_INFO_BASE)) {
            Map<Integer, BitSet> socketToCpu = new HashMap<>();
            Map<Integer, BitSet> socketToCore = new HashMap<>();

            var list = cpuDirs.filter(p -> p.getFileName().toString().matches("cpu\\d+"))
                    .peek(cpuDir -> {
                        int cpu = parseCpu(cpuDir.getFileName().toString());
                        initCacheLayout(cpu, cpuDir.resolve("cache"));
                        try {
                            cpuRankings.get(cpu).capacity[0] *= Long.parseLong(
                                    read(cpuDir.resolve("cpufreq/cpuinfo_max_freq")));
                        } catch (Throwable ignored) {
                            // Not all info is supported
                        }
                    }).toList();
            Int2BooleanArrayMap pCpu = rankCpus();
            list.forEach(cpuDir -> {
                Path topology = cpuDir.resolve("topology");

                int cpu = parseCpu(cpuDir.getFileName().toString());
                int core = cpu;
                int socket = 0;
                String coreCpuSet = "";

                try {
                    core = Integer.parseInt(read(topology.resolve("core_id")));
                    socket = Integer.parseInt(read(topology.resolve("physical_package_id")));
                    coreCpuSet = SystemInfo.toHexMask(
                            parseCpuList(read(topology.resolve("core_cpus_list"))));
                } catch (IOException e) {
                    LOGGER.error("Failed to read topology for CPU: {}", cpu, e);
                }
                cpuInfo.put(cpu, new CpuInfo(cpu, core, socket));
                coreInfo.put(core, new CoreInfo(coreCpuSet, pCpu.get(cpu), core, socket));

                BitSet cpuSet = socketToCpu.computeIfAbsent(socket, k -> new BitSet());
                BitSet coreSet = socketToCore.computeIfAbsent(socket, k -> new BitSet());
                cpuSet.set(cpu);
                coreSet.set(core);
            });
            for (var entry : socketToCore.entrySet()) {
                int socket = entry.getKey();
                socketInfo.put(socket, new SocketInfo(SystemInfo.toHexMask(socketToCpu.get(socket)),
                        SystemInfo.toHexMask(entry.getValue()), socket));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to list cpus.", e);
        }
    }

    private static int parseCpu(String folder) {
        int cpu = 0;
        for (int i = 3; i < folder.length(); i++) {
            char c = folder.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            cpu *= 10;
            cpu += c - '0';
        }
        return cpu;
    }

    private void initCacheLayout(int cpu, Path cachePath) {
        if (Files.exists(cachePath)) {
            int[] cacheLineBytes = {512};
            long[] size = new long[3];
            int[] shared = new int[3];
            String[] masks = new String[3];

            try (var indexes = Files.list(cachePath)) {
                indexes.forEach(index -> {
                    try {
                        String type = read(index.resolve("type"));
                        if (type.toLowerCase().startsWith("instruction")) {
                            return;
                        }

                        int level = Integer.parseInt(read(index.resolve("level")));
                        cacheLineBytes[0] = Math.min(cacheLineBytes[0],
                                Integer.parseInt(read(index.resolve("coherency_line_size"))));
                        size[level - 1] = toBytes(read(index.resolve("size")));
                        masks[level - 1] = read(index.resolve("shared_cpu_map"));
                        shared[level - 1] = parseSharedCount(masks[level - 1]);
                    } catch (IOException ignored) {
                        // Not all info is supported.
                    }
                });
            } catch (Exception e) {
                LOGGER.error("Failed to read cache layout for CPU: {}", cpu, e);
            }
            CpuCacheLayout layout = new CpuCacheLayout(
                    cpu,
                    size[0] <= 0 ? DEFAULT_L1 : size[0],
                    size[1] <= 0 ? DEFAULT_L2 : size[1],
                    size[2] <= 0 ? DEFAULT_L3 : size[2],
                    Math.max(1, shared[0]),
                    Math.max(1, shared[1]),
                    Math.max(1, shared[2]),
                    masks[0], masks[1],
                    masks[2] == null ? "" : masks[2],
                    cacheLineBytes[0]
            );
            cpuCache.put(cpu, layout);
            cpuRankings.compute(cpu, (k, curr) -> {
                if (curr == null) {
                    curr = new Ranking(cpu, new long[2]);
                }
                int l2Div = layout.sharesL2() > 2 ? layout.sharesL2() : 1;
                l2Div += shared[2] == 1 ? 1 : 0;
                curr.capacity[0] += layout.bytesL1() * layout.sharesL1() + layout.bytesL2() / l2Div;
                curr.capacity[1] = layout.sharesL1();
                return curr;
            });
        }
    }

    private Int2BooleanArrayMap rankCpus() {
        Int2BooleanArrayMap pCpu = new Int2BooleanArrayMap(cpuRankings.size());

        Ranking[] sorted = cpuRankings.values().toArray(Ranking[]::new);
        Arrays.sort(sorted);

        int splitIndex = -1;
        long maxGap = -1;

        for (int i = 0; i < sorted.length - 1; i++) {
            long gap = sorted[i].capacity[0] - sorted[i + 1].capacity[0];
            if (gap > maxGap) {
                maxGap = gap;
                splitIndex = i;
            }
        }

        if (maxGap > 0) {
            for (int p = 0; p < sorted.length; p++) {
                pCpu.put(sorted[p].cpu, p <= splitIndex);
            }
        } else {
            for (Ranking ranking : sorted) {
                pCpu.put(ranking.cpu, ranking.capacity[1] > 1);
            }
        }

        return pCpu;
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p).trim();
    }

    private static BitSet parseCpuList(String cpuList) {
        BitSet mask = new BitSet(Runtime.getRuntime().availableProcessors());

        String[] chunks = cpuList.split(",");
        for (String c : chunks) {
            String[] cpus = c.split("-");
            if (cpus.length > 1) {
                mask.set(Integer.parseInt(cpus[0]), Integer.parseInt(cpus[1]) + 1);
            } else {
                mask.set(Integer.parseInt(cpus[0]));
            }
        }

        return mask;
    }

    private static long toBytes(String size) {
        char lastChar = size.charAt(size.length() - 1);
        if (lastChar == 'K' || lastChar == 'k') {
            return Long.parseLong(size.substring(0, size.length() - 1)) * 1024;
        } else if (lastChar == 'M' || lastChar == 'm') {
            return Long.parseLong(size.substring(0, size.length() - 1)) * 1024 * 1024;
        }
        return Long.parseLong(size);
    }

    private static int parseSharedCount(String shared) {
        int cpus = 0;
        String[] groups = shared.split(",");
        for (String g : groups) {
            cpus += Long.bitCount(Long.parseUnsignedLong(g, 16));
        }
        return cpus;
    }

    public Map<Integer, CpuCacheLayout> getCacheLayout() {
        return Collections.unmodifiableMap(this.cpuCache);
    }

    public Map<Integer, CpuInfo> getCpuInfoMap() {
        return Collections.unmodifiableMap(this.cpuInfo);
    }

    public Map<Integer, CoreInfo> getCoreInfoMap() {
        return Collections.unmodifiableMap(this.coreInfo);
    }

    public Map<Integer, SocketInfo> getSocketInfoMap() {
        return Collections.unmodifiableMap(this.socketInfo);
    }

    private record Ranking(int cpu, long[] capacity) implements Comparable<Ranking> {

        @Override
        public int compareTo(Ranking o) {
            // Descending order
            return Long.compare(o.capacity[0], capacity[0]);
        }
    }
}
