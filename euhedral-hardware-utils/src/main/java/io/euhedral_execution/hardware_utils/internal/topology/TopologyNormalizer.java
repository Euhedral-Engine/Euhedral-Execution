package io.euhedral_execution.hardware_utils.internal.topology;

import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class TopologyNormalizer {

    public static final int MAX_LOGICAL_CPU_ID = 1_048_575;
    public static final int MAX_ACTIVE_CPUS = 65_536;
    public static final long MAX_CORE_INDEX_SUM = 16_777_216L;
    private static final long DEFAULT_L1 = 32L * 1024L;
    private static final long DEFAULT_L2 = 256L * 1024L;
    private static final long DEFAULT_L3 = 4L * 1024L * 1024L;
    private static final Comparator<String> KEY_COMPARATOR = TopologyNormalizer::compareKeys;

    private static List<Domain> canonicalDomains(
            List<CacheDomain> input, BitSet active, Map<Integer, CpuInfo> identities) {
        Map<String, Domain> unique = new TreeMap<>();
        for (CacheDomain value : input) {
            if (value == null || value.level() < 1 || value.level() > 3 || value.sizeBytes() <= 0) {
                continue;
            }
            BitSet mask = value.logicalCpuSharers();
            mask.and(active);
            if (mask.isEmpty()) {
                continue;
            }
            int socket = -1;
            boolean crossSocket = false;
            for (int cpu = mask.nextSetBit(0); cpu >= 0; cpu = mask.nextSetBit(cpu + 1)) {
                CpuInfo info = identities.get(cpu);
                if (info == null) {
                    continue;
                }
                if (socket < 0) {
                    socket = info.socket();
                } else if (socket != info.socket()) {
                    crossSocket = true;
                }
            }
            if (crossSocket) {
                continue;
            }
            int line = validLine(value.lineSizeBytes()) ? value.lineSizeBytes() : 64;
            Domain domain = new Domain(value.level(), value.sizeBytes(), line, mask);
            unique.put(domain.key(), domain);
        }
        return List.copyOf(unique.values());
    }

    private static Selection select(List<Domain> domains, int level, int cpu, BitSet fallbackMask, long fallbackBytes) {
        List<Domain> matches = domains.stream()
                .filter(domain -> domain.level == level && domain.mask.get(cpu))
                .toList();
        if (matches.size() == 1) {
            Domain domain = matches.get(0);
            return new Selection(domain.bytes, domain.line, (BitSet) domain.mask.clone());
        }
        return new Selection(fallbackBytes, 64, (BitSet) fallbackMask.clone());
    }

    private static boolean validLine(int line) {
        return line >= 16 && line <= 1024 && (line & (line - 1)) == 0;
    }

    private static void validateKey(String provider, String key, String category) {
        if (key == null || key.isBlank() || !key.equals(key.trim())) {
            throw failure(provider, category, String.valueOf(key), "key must be nonblank and trimmed");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c > 0x7f || Character.toLowerCase(c) != c) {
                throw failure(provider, category, key, "key must be lowercase ASCII");
            }
        }
    }

    private static void validateEncoding(String provider, LogicalCpu cpu) {
        boolean valid =
                switch (provider) {
                    case "linux" ->
                        cpu.socketKey().matches("linux:package:-?(0|[1-9][0-9]*)")
                                && cpu.dieKey().matches("linux:die:-?(0|[1-9][0-9]*)")
                                && cpu.coreKey().matches("linux:core:-?(0|[1-9][0-9]*)");
                    case "windows" ->
                        cpu.socketKey().matches("windows:package:g[0-9]+=[0-9a-f]{16}(;g[0-9]+=[0-9a-f]{16})*")
                                && cpu.dieKey().equals("windows:die:0")
                                && cpu.coreKey().matches("windows:core:g[0-9]+=[0-9a-f]{16}(;g[0-9]+=[0-9a-f]{16})*");
                    case "macos" ->
                        cpu.socketKey().equals("macos:package:0")
                                && cpu.dieKey().equals("macos:die:0")
                                && cpu.coreKey().matches("macos:core:[0-9a-f]{8}");
                    case "fallback" ->
                        cpu.socketKey().equals("fallback:package:0")
                                && cpu.dieKey().equals("fallback:die:0")
                                && cpu.coreKey().matches("fallback:core:[0-9a-f]{8}");
                    default -> false;
                };
        if (!valid) {
            throw failure(
                    provider,
                    "source-key",
                    Integer.toString(cpu.logicalCpuId()),
                    "keys do not match the provider's canonical encoding");
        }
    }

    private static int compareKeys(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(a[i]), Byte.toUnsignedInt(b[i]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static TopologyValidationException failure(String provider, String category, String value, String reason) {
        return new TopologyValidationException(provider, category, value, reason);
    }

    public TopologyModel normalize(TopologyInput source) {
        if (source == null) {
            throw failure("unknown", "input", "null", "input is null");
        }
        String provider = source.providerName();
        validateKey(provider, provider, "provider");
        List<LogicalCpu> cpus = new ArrayList<>(source.logicalCpus().size());
        Set<Integer> ids = new HashSet<>();
        if (source.logicalCpus().isEmpty()) {
            throw failure(provider, "logical-cpus", "empty", "at least one CPU is required");
        }
        if (source.logicalCpus().size() > MAX_ACTIVE_CPUS) {
            throw failure(
                    provider,
                    "active-count",
                    Integer.toString(source.logicalCpus().size()),
                    "active CPU count exceeds " + MAX_ACTIVE_CPUS);
        }
        for (LogicalCpu value : source.logicalCpus()) {
            if (value == null) {
                throw failure(provider, "logical-cpu", "null", "CPU entry is null");
            }
            int id = value.logicalCpuId();
            if (id < 0 || id > MAX_LOGICAL_CPU_ID) {
                throw failure(
                        provider,
                        "logical-id",
                        Integer.toString(id),
                        "logical ID is outside [0," + MAX_LOGICAL_CPU_ID + "]");
            }
            if (!ids.add(id)) {
                throw failure(provider, "logical-id", Integer.toString(id), "logical ID is duplicated");
            }
            validateKey(provider, value.socketKey(), "socket-key");
            validateKey(provider, value.dieKey(), "die-key");
            validateKey(provider, value.coreKey(), "core-key");
            validateEncoding(provider, value);
            cpus.add(new LogicalCpu(id, value.socketKey(), value.dieKey(), value.coreKey(), value.coreKind()));
        }
        cpus.sort(Comparator.comparingInt(LogicalCpu::logicalCpuId));

        List<String> socketKeys = cpus.stream()
                .map(LogicalCpu::socketKey)
                .distinct()
                .sorted(KEY_COMPARATOR)
                .toList();
        Map<String, Integer> sockets = new HashMap<>();
        for (int i = 0; i < socketKeys.size(); i++) {
            sockets.put(socketKeys.get(i), i);
        }

        record CoreKey(int socket, String die, String core) {}

        Comparator<CoreKey> coreComparator = Comparator.comparingInt(CoreKey::socket)
                .thenComparing(CoreKey::die, KEY_COMPARATOR)
                .thenComparing(CoreKey::core, KEY_COMPARATOR);
        List<CoreKey> coreKeys = cpus.stream()
                .map(cpu -> new CoreKey(sockets.get(cpu.socketKey()), cpu.dieKey(), cpu.coreKey()))
                .distinct()
                .sorted(coreComparator)
                .toList();
        Map<CoreKey, Integer> cores = new HashMap<>();
        for (int i = 0; i < coreKeys.size(); i++) {
            cores.put(coreKeys.get(i), i);
        }

        int[] highestCpuByCore = new int[coreKeys.size()];
        java.util.Arrays.fill(highestCpuByCore, -1);
        for (LogicalCpu cpu : cpus) {
            int socket = sockets.get(cpu.socketKey());
            int core = cores.get(new CoreKey(socket, cpu.dieKey(), cpu.coreKey()));
            highestCpuByCore[core] = Math.max(highestCpuByCore[core], cpu.logicalCpuId());
        }
        long indexSum = 0;
        for (int highest : highestCpuByCore) {
            indexSum += (long) highest + 1L;
        }
        if (indexSum > MAX_CORE_INDEX_SUM) {
            throw failure(
                    provider,
                    "core-index-sum",
                    Long.toString(indexSum),
                    "core index sum exceeds " + MAX_CORE_INDEX_SUM);
        }

        Map<Integer, BitSet> coreCpus = new TreeMap<>();
        Map<Integer, BitSet> socketCpus = new TreeMap<>();
        Map<Integer, BitSet> socketCores = new TreeMap<>();
        Map<Integer, CoreKind> coreKinds = new HashMap<>();
        Map<Integer, CpuInfo> cpuInfo = new TreeMap<>();
        for (LogicalCpu cpu : cpus) {
            int socket = sockets.get(cpu.socketKey());
            int core = cores.get(new CoreKey(socket, cpu.dieKey(), cpu.coreKey()));
            CoreKind previous = coreKinds.putIfAbsent(core, cpu.coreKind());
            if (previous != null && previous != cpu.coreKind()) {
                throw failure(provider, "core-kind", cpu.coreKey(), "logical siblings disagree on core kind");
            }
            coreCpus.computeIfAbsent(core, ignored -> new BitSet()).set(cpu.logicalCpuId());
            socketCpus.computeIfAbsent(socket, ignored -> new BitSet()).set(cpu.logicalCpuId());
            socketCores.computeIfAbsent(socket, ignored -> new BitSet()).set(core);
            cpuInfo.put(cpu.logicalCpuId(), new CpuInfo(cpu.logicalCpuId(), core, socket));
        }
        Map<Integer, CoreInfo> coreInfo = new TreeMap<>();
        BitSet pCores = new BitSet();
        BitSet eCores = new BitSet();
        BitSet pCpus = new BitSet();
        BitSet eCpus = new BitSet();
        for (Map.Entry<Integer, BitSet> entry : coreCpus.entrySet()) {
            int core = entry.getKey();
            int firstCpu = entry.getValue().nextSetBit(0);
            int socket = cpuInfo.get(firstCpu).socket();
            boolean performance = coreKinds.get(core) != CoreKind.EFFICIENCY;
            coreInfo.put(core, new CoreInfo(MaskCodec.format(entry.getValue()), performance, core, socket));
            (performance ? pCores : eCores).set(core);
            (performance ? pCpus : eCpus).or(entry.getValue());
        }
        Map<Integer, SocketInfo> socketInfo = new TreeMap<>();
        for (int socket = 0; socket < socketKeys.size(); socket++) {
            socketInfo.put(
                    socket,
                    new SocketInfo(
                            MaskCodec.format(socketCpus.get(socket)),
                            MaskCodec.format(socketCores.get(socket)),
                            socket));
        }

        BitSet active = new BitSet();
        ids.forEach(active::set);
        List<Domain> domains = canonicalDomains(source.cacheDomains(), active, cpuInfo);
        Map<Integer, CpuCacheLayout> layouts = new TreeMap<>();
        int maxLine = 64;
        for (LogicalCpu cpu : cpus) {
            CpuInfo identity = cpuInfo.get(cpu.logicalCpuId());
            Selection l1 = select(domains, 1, cpu.logicalCpuId(), coreCpus.get(identity.core()), DEFAULT_L1);
            Selection l2 = select(domains, 2, cpu.logicalCpuId(), coreCpus.get(identity.core()), DEFAULT_L2);
            Selection l3 = select(domains, 3, cpu.logicalCpuId(), socketCpus.get(identity.socket()), DEFAULT_L3);
            maxLine = Math.max(maxLine, Math.max(l1.line, Math.max(l2.line, l3.line)));
            layouts.put(
                    cpu.logicalCpuId(),
                    new CpuCacheLayout(
                            cpu.logicalCpuId(),
                            l1.bytes,
                            l2.bytes,
                            l3.bytes,
                            l1.mask.cardinality(),
                            l2.mask.cardinality(),
                            l3.mask.cardinality(),
                            MaskCodec.format(l1.mask),
                            MaskCodec.format(l2.mask),
                            MaskCodec.format(l3.mask),
                            Math.max(l1.line, Math.max(l2.line, l3.line))));
        }

        int[] activeIds = cpus.stream().mapToInt(LogicalCpu::logicalCpuId).toArray();
        return new TopologyModel(
                activeIds,
                UnmodifiableBitSet.wrap(active),
                UnmodifiableBitSet.wrap(pCores),
                UnmodifiableBitSet.wrap(eCores),
                UnmodifiableBitSet.wrap(pCpus),
                UnmodifiableBitSet.wrap(eCpus),
                layouts,
                cpuInfo,
                coreInfo,
                socketInfo,
                cpus.get(cpus.size() - 1).logicalCpuId() + 1,
                coreKeys.size(),
                socketKeys.size(),
                maxLine);
    }

    private record Domain(int level, long bytes, int line, BitSet mask) {

        private Domain {
            mask = (BitSet) mask.clone();
        }

        private String key() {
            return level + ":" + bytes + ":" + line + ":" + MaskCodec.format(mask);
        }
    }

    private record Selection(long bytes, int line, BitSet mask) {}
}
