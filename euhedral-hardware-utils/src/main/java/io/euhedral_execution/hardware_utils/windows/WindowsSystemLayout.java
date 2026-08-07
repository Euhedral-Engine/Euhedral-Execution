package io.euhedral_execution.hardware_utils.windows;

import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;
import io.euhedral_execution.hardware_utils.internal.topology.CacheDomain;
import io.euhedral_execution.hardware_utils.internal.topology.CoreKind;
import io.euhedral_execution.hardware_utils.internal.topology.LogicalCpu;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyBootstrap;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyInput;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyModel;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyNormalizer;
import io.euhedral_execution.hardware_utils.windows.win32.CacheRelationship;
import io.euhedral_execution.hardware_utils.windows.win32.CacheRelationship.CacheType;
import io.euhedral_execution.hardware_utils.windows.win32.GroupAffinity;
import io.euhedral_execution.hardware_utils.windows.win32.ProcessorRelationship;
import io.euhedral_execution.hardware_utils.windows.win32.Relationship;
import io.euhedral_execution.hardware_utils.windows.win32.SystemLogicalProcessorInformation;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WindowsSystemLayout {

    public static final WindowsSystemLayout INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(
            WindowsSystemLayout.class));

    static {
        INSTANCE = OSName.isWindows() ? new WindowsSystemLayout() : null;
    }

    private static native byte[] getRawTopologyInfo();

    private static TopologyInput translate(List<SystemLogicalProcessorInformation> relationships) {
        List<ProcessorRelationship> packages = new ArrayList<>();
        List<ProcessorRelationship> cores = new ArrayList<>();
        List<CacheDomain> caches = new ArrayList<>();
        for (SystemLogicalProcessorInformation relationship : relationships) {
            if (relationship instanceof ProcessorRelationship processor) {
                if (processor.relationship == Relationship.PROCESSOR_PACKAGE) {
                    packages.add(processor);
                } else if (processor.relationship == Relationship.PROCESSOR_CORE) {
                    cores.add(processor);
                }
            } else if (relationship instanceof CacheRelationship cache
                    && cache.type != CacheType.INSTRUCTION) {
                BitSet mask = affinityMask(cache.groupAffinities);
                caches.add(new CacheDomain(Byte.toUnsignedInt(cache.level), cache.cacheSizeBytes,
                        Short.toUnsignedInt(cache.lineSize), mask));
            }
        }

        Map<Integer, Set<String>> packageOwners = new HashMap<>();
        for (ProcessorRelationship value : packages) {
            String signature = signature("package", value.groupAffinities);
            forEachCpu(value.groupAffinities, cpu -> packageOwners.computeIfAbsent(cpu,
                    ignored -> new HashSet<>()).add(signature));
        }
        boolean hasTrue = cores.stream().anyMatch(core -> core.pCore);
        boolean hasFalse = cores.stream().anyMatch(core -> !core.pCore);
        Map<Integer, LogicalCpu> cpus = new TreeMap<>();
        for (ProcessorRelationship value : cores) {
            String coreSignature = signature("core", value.groupAffinities);
            CoreKind kind = (hasTrue && hasFalse) ? (value.pCore ? CoreKind.PERFORMANCE
                    : CoreKind.EFFICIENCY)
                    : CoreKind.UNKNOWN;
            forEachCpu(value.groupAffinities, cpu -> {
                Set<String> owners = packageOwners.get(cpu);
                if (owners == null || owners.size() != 1) {
                    throw new IllegalArgumentException("windows CPU " + cpu
                            + " must have exactly one package owner");
                }
                LogicalCpu prior = cpus.putIfAbsent(cpu,
                        new LogicalCpu(cpu, owners.iterator().next(),
                                "windows:die:0", coreSignature, kind));
                if (prior != null && (!prior.coreKey().equals(coreSignature)
                        || prior.coreKind() != kind)) {
                    throw new IllegalArgumentException("windows CPU " + cpu
                            + " has conflicting core ownership");
                }
            });
        }
        return new TopologyInput("windows", List.copyOf(cpus.values()), caches);
    }

    private static String signature(String type, List<GroupAffinity> affinities) {
        List<GroupAffinity> nonzero = affinities.stream().filter(value -> value.mask() != 0)
                .sorted(Comparator.comparingInt(value -> Short.toUnsignedInt(value.group())))
                .toList();
        if (nonzero.isEmpty()) {
            throw new IllegalArgumentException("empty windows " + type + " affinity");
        }
        StringBuilder value = new StringBuilder("windows:").append(type).append(':');
        for (int i = 0; i < nonzero.size(); i++) {
            if (i > 0) {
                value.append(';');
            }
            GroupAffinity affinity = nonzero.get(i);
            value.append('g').append(Short.toUnsignedInt(affinity.group())).append('=')
                    .append(String.format(Locale.ROOT, "%016x", affinity.mask()));
        }
        return value.toString();
    }

    private static BitSet affinityMask(List<GroupAffinity> values) {
        BitSet result = new BitSet();
        forEachCpu(values, result::set);
        return result;
    }

    private static void forEachCpu(List<GroupAffinity> values,
            java.util.function.IntConsumer consumer) {
        for (GroupAffinity affinity : values) {
            int group = Short.toUnsignedInt(affinity.group());
            long mask = affinity.mask();
            while (mask != 0) {
                int processor = Long.numberOfTrailingZeros(mask);
                long logical = (long) group * 64L + processor;
                consumer.accept((int) logical);
                mask &= mask - 1;
            }
        }
    }

    private static List<SystemLogicalProcessorInformation> copyRelationships(
            List<SystemLogicalProcessorInformation> source) {
        List<SystemLogicalProcessorInformation> result = new ArrayList<>(
                List.copyOf(source).size());
        for (SystemLogicalProcessorInformation value : source) {
            if (value instanceof ProcessorRelationship processor) {
                result.add(new ProcessorRelationship(processor.relationship, processor.smt,
                        processor.pCore, copyAffinities(processor.groupAffinities)));
            } else if (value instanceof CacheRelationship cache) {
                result.add(new CacheRelationship(cache.level, cache.associativity, cache.lineSize,
                        cache.cacheSizeBytes, cache.type, copyAffinities(cache.groupAffinities)));
            } else {
                result.add(value);
            }
        }
        return List.copyOf(result);
    }

    private static List<GroupAffinity> copyAffinities(List<GroupAffinity> source) {
        return source.stream().map(value -> new GroupAffinity(value.mask(), value.group()))
                .toList();
    }

    private final TopologyModel model;

    private WindowsSystemLayout() {
        this(() -> {
            JNIClassLoader.load();
            byte[] raw = getRawTopologyInfo();
            return translate(
                    raw == null ? List.of() : SystemLogicalProcessorInformation.parse(raw));
        });
    }

    WindowsSystemLayout(List<SystemLogicalProcessorInformation> relationships) {
        this.model = new TopologyNormalizer().normalize(
                translate(copyRelationships(relationships)));
    }

    private WindowsSystemLayout(
            io.euhedral_execution.hardware_utils.internal.topology.TopologyProvider provider) {
        this.model = TopologyBootstrap.normalize(provider,
                Runtime.getRuntime().availableProcessors(), LOGGER, "windows");
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
}
