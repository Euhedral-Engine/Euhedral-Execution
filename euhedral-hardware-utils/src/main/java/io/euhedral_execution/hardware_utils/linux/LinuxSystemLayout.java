package io.euhedral_execution.hardware_utils.linux;

import io.euhedral_execution.hardware_utils.topology.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.topology.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.topology.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.topology.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.topology.internal.CacheDomain;
import io.euhedral_execution.hardware_utils.topology.internal.CoreKind;
import io.euhedral_execution.hardware_utils.topology.internal.LogicalCpu;
import io.euhedral_execution.hardware_utils.topology.internal.MaskCodec;
import io.euhedral_execution.hardware_utils.topology.internal.TopologyBootstrap;
import io.euhedral_execution.hardware_utils.topology.internal.TopologyInput;
import io.euhedral_execution.hardware_utils.topology.internal.TopologyModel;
import io.euhedral_execution.hardware_utils.topology.internal.TopologyNormalizer;
import io.euhedral_execution.hardware_utils.topology.internal.TopologyProvider;
import io.euhedral_execution.hardware_utils.util.OSName;
import io.euhedral_execution.hardware_utils.util.internal.Constants;
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

/// Linux CPU topology provider and sysfs directory scanner.
///
/// Scans sysfs `/sys/devices/system/cpu/` to discover logical CPUs, physical packages,
/// dies, cores, cache domains, and cpufreq scaling information. Translates observations
/// into normalized `TopologyModel` instances with sparse OS CPU ID indexing and explicit
/// `null` holes for offline or unmapped CPU IDs.
public final class LinuxSystemLayout {

    public static final LinuxSystemLayout INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(LinuxSystemLayout.class));
    private static final Path CPU_ROOT = Path.of("/sys/devices/system/cpu");

    static {
        INSTANCE = OSName.isLinux() ? new LinuxSystemLayout(CPU_ROOT) : null;
    }

    private final TopologyModel model;

    /// Constructs a `LinuxSystemLayout` by scanning the specified sysfs CPU root directory.
    LinuxSystemLayout(Path cpuRoot) {
        this(() -> collect(cpuRoot));
    }

    /// Constructs a `LinuxSystemLayout` using a custom `TopologyProvider` functional interface.
    LinuxSystemLayout(TopologyProvider provider) {
        this.model = TopologyBootstrap.normalize(provider, Runtime.getRuntime().availableProcessors(), LOGGER, "linux");
    }

    /// Scans sysfs CPU root directory for `cpu\d+` subdirectories, collecting topology
    /// attributes, cache domains, and core classification data. Returns an empty `TopologyInput`
    /// if `cpuRoot` is absent or unreadable.
    private static TopologyInput collect(Path cpuRoot) {
        if (cpuRoot == null || !Files.isDirectory(cpuRoot)) {
            return new TopologyInput("linux", List.of(), List.of());
        }
        List<Path> directories;
        try (var paths = Files.list(cpuRoot)) {
            directories = paths.filter(path -> path.getFileName().toString().matches("cpu\\d+"))
                    .sorted(Comparator.comparingInt(
                            path -> parseCpu(path.getFileName().toString())))
                    .toList();
        } catch (IOException e) {
            return new TopologyInput("linux", List.of(), List.of());
        }
        directories = retainOnline(cpuRoot, directories);
        List<RawCpu> raw = new ArrayList<>(directories.size());
        List<CacheDomain> caches = new ArrayList<>();
        for (Path directory : directories) {
            int cpu = parseCpu(directory.getFileName().toString());
            Path topology = directory.resolve("topology");
            // A CPU without package or core identity must not merge into package 0 / core 0.
            // Missing package ids share one unknown package (no socket inflation); missing core ids are
            // unique per CPU. die_id is absent on many kernels and keeps defaulting.
            int packageId = parseAttrOrDefault(topology.resolve("physical_package_id"), UNKNOWN_PACKAGE_ID);
            int coreId = parseAttrOrDefault(topology.resolve("core_id"), syntheticCoreId(cpu));
            int dieId = parseAttrOrDefault(topology.resolve("die_id"), 0);
            CacheScore cacheScore = collectCaches(directory.resolve("cache"), caches);
            raw.add(new RawCpu(cpu, packageId, dieId, coreId, frequencyScore(directory), cacheScore.value));
        }
        Map<CoreTuple, CoreKind> kinds = classify(raw);
        List<LogicalCpu> cpus = raw.stream()
                .map(cpu -> {
                    CoreTuple tuple = new CoreTuple(cpu.packageId, cpu.dieId, cpu.coreId);
                    return new LogicalCpu(
                            cpu.id,
                            "linux:package:" + cpu.packageId,
                            "linux:die:" + cpu.dieId,
                            "linux:core:" + cpu.coreId,
                            kinds.getOrDefault(tuple, CoreKind.UNKNOWN));
                })
                .toList();
        return new TopologyInput("linux", cpus, caches);
    }

    /// Shared package id for every CPU lacking `physical_package_id`. The linux key encoding accepts only
    /// decimal integers, so this is `Integer.MIN_VALUE`, which sysfs never reports. Note that the
    /// normalizer orders keys byte-wise, so `linux:package:-2147483648` sorts before `linux:package:0`
    /// and takes socket index 0, shifting real packages up by one when any CPU lacks a package id.
    private static final int UNKNOWN_PACKAGE_ID = Integer.MIN_VALUE;

    /// Synthesized core id, unique per CPU and distinct from [#UNKNOWN_PACKAGE_ID] and real core ids.
    /// Without an `online` file an offline directory lacking `topology/` also lands here: it becomes
    /// its own core under the unknown package rather than a sibling of core 0.
    private static int syntheticCoreId(int cpu) {
        return Integer.MIN_VALUE + 1 + cpu;
    }

    /// Drops CPU directories that `<cpuRoot>/online` lists as offline. An absent or malformed file keeps
    /// every directory.
    private static List<Path> retainOnline(Path cpuRoot, List<Path> directories) {
        Path file = cpuRoot.resolve("online");
        if (!Files.isRegularFile(file)) {
            return directories;
        }
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            LOGGER.debug("Cannot read online CPU list {}; using all CPU directories", file, e);
            return directories;
        }
        BitSet online = parseCpuList(text);
        if (online == null) {
            LOGGER.debug("Malformed online CPU list '{}'; using all CPU directories", text.trim());
            return directories;
        }
        return directories.stream()
                .filter(path -> online.get(parseCpu(path.getFileName().toString())))
                .toList();
    }

    /// Parses Linux cpulist syntax such as `0-3,6,8-11`. Returns `null` (unknown) for empty or
    /// malformed input, including reversed ranges.
    static BitSet parseCpuList(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        BitSet result = new BitSet();
        int start = 0;
        while (start <= trimmed.length()) {
            int comma = trimmed.indexOf(',', start);
            int end = comma < 0 ? trimmed.length() : comma;
            String item = trimmed.substring(start, end).trim();
            int dash = item.indexOf('-');
            int low;
            int high;
            if (dash < 0) {
                low = parseCpuNumber(item);
                high = low;
            } else {
                low = parseCpuNumber(item.substring(0, dash).trim());
                high = parseCpuNumber(item.substring(dash + 1).trim());
            }
            if (low < 0 || high < low) {
                return null;
            }
            result.set(low, high + 1);
            if (comma < 0) {
                break;
            }
            start = comma + 1;
        }
        return result;
    }

    /// Parses a non-negative decimal CPU number of plain digits, or returns -1.
    private static int parseCpuNumber(String value) {
        if (value.isEmpty() || value.length() > 7) {
            return -1;
        }
        int result = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            result = result * 10 + (c - '0');
        }
        return result <= TopologyNormalizer.MAX_LOGICAL_CPU_ID ? result : -1;
    }

    /// Safely parses an integer attribute from a file, returning `defaultValue` if the file
    /// is missing, empty, or unreadable.
    private static int parseAttrOrDefault(Path file, int defaultValue) {
        if (!Files.isRegularFile(file)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(read(file));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    /// Scans a `cpuX/cache/` directory for `indexY` cache domain subdirectories.
    /// Skips instruction caches and extracts data/unified cache domains.
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
                        l2 = size / (sharers.cardinality() > 2 ? sharers.cardinality() : 1);
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

    /// Reads maximum CPU frequency in kHz from `cpufreq/cpuinfo_max_freq`. Returns -1 if absent or unreadable.
    private static long frequencyScore(Path cpu) {
        try {
            long frequency = Long.parseLong(read(cpu.resolve("cpufreq/cpuinfo_max_freq")));
            return frequency > 0 ? frequency : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    /// Classifies cores into `PERFORMANCE` vs `EFFICIENCY` by scoring cpufreq max frequency or cache capacity
    /// and finding the maximum score gap across distinct core tuples.
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
                .sorted(Map.Entry.<CoreTuple, Long>comparingByValue()
                        .reversed()
                        .thenComparing(entry -> entry.getKey().toString()))
                .toList();
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
                result.put(sorted.get(i).getKey(), i <= boundary ? CoreKind.PERFORMANCE : CoreKind.EFFICIENCY);
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
            return Long.parseLong(value.substring(0, value.length() - 1)) * 1024L;
        }
        if (suffix == 'M' || suffix == 'm') {
            return Long.parseLong(value.substring(0, value.length() - 1)) * 1024L * 1024L;
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

    private record RawCpu(int id, int packageId, int dieId, int coreId, long frequencyScore, long cacheScore) {}

    private record CacheScore(long value) {

        private static final CacheScore INVALID = new CacheScore(-1);
    }

    private record CoreTuple(int packageId, int dieId, int coreId) {}
}
