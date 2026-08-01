package io.euhedral_execution.hardware_utils.linux;

import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.topology.CacheDomain;
import io.euhedral_execution.hardware_utils.internal.topology.CoreKind;
import io.euhedral_execution.hardware_utils.internal.topology.LogicalCpu;
import io.euhedral_execution.hardware_utils.internal.topology.MaskCodec;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyBootstrap;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyInput;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyModel;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LinuxSystemLayout {

    public static final LinuxSystemLayout INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(
            LinuxSystemLayout.class));
    private static final Path CPU_ROOT = Path.of("/sys/devices/system/cpu");

    static {
        INSTANCE = OSName.isLinux() ? new LinuxSystemLayout(CPU_ROOT) : null;
    }

    private static TopologyInput collect(Path cpuRoot) throws IOException {
        List<Path> directories;
        try (var paths = Files.list(cpuRoot)) {
            directories = paths.filter(path -> path.getFileName().toString().matches("cpu\\d+"))
                    .sorted(Comparator.comparingInt(
                            path -> parseCpu(path.getFileName().toString())))
                    .toList();
        }
        List<RawCpu> raw = new ArrayList<>(directories.size());
        List<CacheDomain> caches = new ArrayList<>();
        for (Path directory : directories) {
            int cpu = parseCpu(directory.getFileName().toString());
            Path topology = directory.resolve("topology");
            int packageId = parseSigned(read(topology.resolve("physical_package_id")));
            int coreId = parseSigned(read(topology.resolve("core_id")));
            int dieId = Files.isRegularFile(topology.resolve("die_id"))
                    ? parseSigned(read(topology.resolve("die_id"))) : 0;
            CacheScore cacheScore = collectCaches(directory.resolve("cache"), caches);
            raw.add(new RawCpu(cpu, packageId, dieId, coreId, frequencyScore(directory),
                    cacheScore.value));
        }
        Map<CoreTuple, CoreKind> kinds = classify(raw);
        List<LogicalCpu> cpus = raw.stream().map(cpu -> {
            CoreTuple tuple = new CoreTuple(cpu.packageId, cpu.dieId, cpu.coreId);
            return new LogicalCpu(cpu.id, "linux:package:" + cpu.packageId,
                    "linux:die:" + cpu.dieId, "linux:core:" + cpu.coreId,
                    kinds.getOrDefault(tuple, CoreKind.UNKNOWN));
        }).toList();
        return new TopologyInput("linux", cpus, caches);
    }

    private static CacheScore collectCaches(Path root, List<CacheDomain> target) {
        if (!Files.isDirectory(root)) {
            return CacheScore.INVALID;
        }
        long l1 = -1;
        long l2 = -1;
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                try {
                    String type = read(entry.resolve("type")).toLowerCase();
                    if (type.startsWith("instruction")) {
                        continue;
                    }
                    int level = Integer.parseInt(read(entry.resolve("level")));
                    long size = toBytes(read(entry.resolve("size")));
                    int line = Integer.parseInt(read(entry.resolve("coherency_line_size")));
                    BitSet sharers = MaskCodec.parse(read(entry.resolve("shared_cpu_map")));
                    target.add(new CacheDomain(level, size, line, sharers));
                    if (level == 1) {
                        l1 = saturatedMultiply(size, sharers.cardinality());
                    }
                    if (level == 2) {
                        l2 = size / (sharers.cardinality() > 2
                                ? sharers.cardinality() : 1);
                    }
                } catch (Exception ignored) {
                    // Cache observations are optional and completed by the common normalizer.
                }
            }
        } catch (IOException ignored) {
            // Cache observations are optional and completed by the common normalizer.
        }
        return l1 > 0 && l2 > 0 ? new CacheScore(saturatedAdd(l1, l2)) : CacheScore.INVALID;
    }

    private static long frequencyScore(Path cpu) {
        try {
            long frequency = Long.parseLong(read(cpu.resolve("cpufreq/cpuinfo_max_freq")));
            return frequency > 0 ? frequency : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static Map<CoreTuple, CoreKind> classify(List<RawCpu> cpus) {
        boolean frequenciesComplete = cpus.stream().allMatch(cpu -> cpu.frequencyScore > 0);
        boolean cachesComplete = cpus.stream().allMatch(cpu -> cpu.cacheScore > 0);
        boolean complete = frequenciesComplete || cachesComplete;
        Map<CoreTuple, Long> scores = new HashMap<>();
        for (RawCpu cpu : cpus) {
            CoreTuple tuple = new CoreTuple(cpu.packageId, cpu.dieId, cpu.coreId);
            long score = frequenciesComplete ? cpu.frequencyScore : cpu.cacheScore;
            Long prior = scores.putIfAbsent(tuple, score);
            if (score <= 0 || prior != null && prior != score) {
                complete = false;
            }
        }
        Map<CoreTuple, CoreKind> result = new HashMap<>();
        scores.keySet().forEach(key -> result.put(key, CoreKind.UNKNOWN));
        if (!complete || scores.size() < 2 || new java.util.HashSet<>(scores.values()).size() < 2) {
            return result;
        }
        List<Map.Entry<CoreTuple, Long>> sorted = scores.entrySet().stream()
                .sorted(Map.Entry.<CoreTuple, Long>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey().toString())).toList();
        long largest = 0;
        int boundary = -1;
        for (int i = 0; i + 1 < sorted.size(); i++) {
            long gap = sorted.get(i).getValue() - sorted.get(i + 1).getValue();
            if (gap > largest) {
                largest = gap;
                boundary = i;
            }
        }
        if (largest > 0) {
            for (int i = 0; i < sorted.size(); i++) {
                result.put(sorted.get(i).getKey(),
                        i <= boundary ? CoreKind.PERFORMANCE : CoreKind.EFFICIENCY);
            }
        }
        return result;
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path).trim();
    }

    private static int parseSigned(String value) {
        return Integer.parseInt(value);
    }

    private static int parseCpu(String folder) {
        return Integer.parseInt(folder.substring(3));
    }

    private static long toBytes(String value) {
        char suffix = value.charAt(value.length() - 1);
        if (suffix == 'K' || suffix == 'k') {
            return Long.parseLong(value.substring(0,
                    value.length() - 1)) * 1024L;
        }
        if (suffix == 'M' || suffix == 'm') {
            return Long.parseLong(value.substring(0,
                    value.length() - 1)) * 1024L * 1024L;
        }
        return Long.parseLong(value);
    }

    private static long saturatedMultiply(long left, int right) {
        if (left <= 0 || right <= 0) {
            return -1;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long saturatedAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private final TopologyModel model;

    LinuxSystemLayout(Path cpuRoot) {
        this(() -> collect(cpuRoot));
    }

    LinuxSystemLayout(TopologyProvider provider) {
        this.model = TopologyBootstrap.normalize(provider,
                Runtime.getRuntime().availableProcessors(), LOGGER, "linux");
    }

    public Map<Integer, CpuCacheLayout> getCacheLayout() {
        return model.cacheLayout();
    }

    public Map<Integer, CpuInfo> getCpuInfoMap() {
        return model.cpuInfo();
    }

    public Map<Integer, CoreInfo> getCoreInfoMap() {
        return model.coreInfo();
    }

    public Map<Integer, SocketInfo> getSocketInfoMap() {
        return model.socketInfo();
    }

    private record RawCpu(int id, int packageId, int dieId, int coreId, long frequencyScore,
                          long cacheScore) {

    }

    private record CacheScore(long value) {

        private static final CacheScore INVALID = new CacheScore(-1);
    }

    private record CoreTuple(int packageId, int dieId, int coreId) {

    }
}
