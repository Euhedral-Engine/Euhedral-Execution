package io.euhedral_execution.hardware_utils.macos;

import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.topology.CacheDomain;
import io.euhedral_execution.hardware_utils.internal.topology.CoreKind;
import io.euhedral_execution.hardware_utils.internal.topology.LogicalCpu;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyBootstrap;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyInput;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyModel;
import io.euhedral_execution.hardware_utils.macos.sysctl.SysctlInt;
import io.euhedral_execution.hardware_utils.macos.sysctl.SysctlLong;
import io.euhedral_execution.hardware_utils.macos.sysctl.SysctlNative;
import io.euhedral_execution.hardware_utils.macos.sysctl.SysctlProvider;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// macOS CPU topology provider with sysctl key discovery and P/E core classification.
public final class MacosSystemLayout {

    public static final MacosSystemLayout INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(MacosSystemLayout.class));

    static {
        INSTANCE = OSName.isMacOS() ? new MacosSystemLayout() : null;
    }

    private final TopologyModel model;

    public MacosSystemLayout() {
        this(SysctlNative.INSTANCE);
    }

    public MacosSystemLayout(SysctlProvider provider) {
        this.model = TopologyBootstrap.normalize(
                () -> collect(provider), Runtime.getRuntime().availableProcessors(), LOGGER, "macos");
    }

    public static native long getSysctlLong(String key);

    public static native int getSysctlInt(String key);

    public static native String getSysctlString(String key);

    private static TopologyInput collect(SysctlProvider provider) {
        if (provider == null) {
            return fallbackInput(Runtime.getRuntime().availableProcessors());
        }

        OptionalInt logicalOpt = SysctlInt.query(provider, "hw.logicalcpu");
        int logicalCpus = logicalOpt.orElseGet(() -> Runtime.getRuntime().availableProcessors());
        if (logicalCpus <= 0) {
            logicalCpus = Runtime.getRuntime().availableProcessors();
        }

        OptionalInt physicalOpt = SysctlInt.query(provider, "hw.physicalcpu");
        int physicalCpus = physicalOpt.orElse(logicalCpus);
        if (physicalCpus <= 0) {
            physicalCpus = logicalCpus;
        }

        OptionalInt nperfOpt = SysctlInt.query(provider, "hw.nperflevels");
        int nperflevels = nperfOpt.orElse(0);

        List<LogicalCpu> cpus = new ArrayList<>();
        List<CacheDomain> caches = new ArrayList<>();

        if (nperflevels >= 2) {
            int pCount = SysctlInt.query(provider, "hw.perflevel0.logicalcpu").orElse(0);
            int eCount = SysctlInt.query(provider, "hw.perflevel1.logicalcpu").orElse(0);

            if (pCount > 0 && eCount > 0 && eCount + pCount == logicalCpus) {
                for (int i = 0; i < eCount; i++) {
                    cpus.add(new LogicalCpu(
                            i,
                            "macos:package:0",
                            "macos:die:0",
                            "macos:core:" + String.format("%08x", i),
                            CoreKind.EFFICIENCY));
                }
                for (int i = 0; i < pCount; i++) {
                    int cpuId = eCount + i;
                    cpus.add(new LogicalCpu(
                            cpuId,
                            "macos:package:0",
                            "macos:die:0",
                            "macos:core:" + String.format("%08x", cpuId),
                            CoreKind.PERFORMANCE));
                }
            } else {
                buildHomogeneousCpus(logicalCpus, physicalCpus, cpus);
            }
        } else {
            buildHomogeneousCpus(logicalCpus, physicalCpus, cpus);
        }

        long l1dSize = SysctlLong.query(provider, "hw.l1dcachesize").orElse(0L);
        long l2Size = SysctlLong.query(provider, "hw.l2cachesize").orElse(0L);
        long l3Size = SysctlLong.query(provider, "hw.l3cachesize").orElse(0L);
        int lineSize = SysctlInt.query(provider, "hw.cachelinesize").orElse(64);
        if (lineSize <= 0) {
            lineSize = 64;
        }

        SysctlLong.query(provider, "hw.l1icachesize");

        if (l1dSize > 0) {
            Map<String, BitSet> coreBitsets = new LinkedHashMap<>();
            for (LogicalCpu cpu : cpus) {
                coreBitsets.computeIfAbsent(cpu.coreKey(), k -> new BitSet()).set(cpu.logicalCpuId());
            }
            for (BitSet bitset : coreBitsets.values()) {
                caches.add(new CacheDomain(1, l1dSize, lineSize, bitset));
            }
        }

        if (l2Size > 0) {
            if (nperflevels >= 2) {
                int pCount =
                        SysctlInt.query(provider, "hw.perflevel0.logicalcpu").orElse(0);
                int eCount =
                        SysctlInt.query(provider, "hw.perflevel1.logicalcpu").orElse(0);
                int pL2Cluster =
                        SysctlInt.query(provider, "hw.perflevel0.cpusperl2").orElse(pCount);
                int eL2Cluster =
                        SysctlInt.query(provider, "hw.perflevel1.cpusperl2").orElse(eCount);

                if (pCount > 0 && eCount > 0 && eCount + pCount == logicalCpus) {
                    int stepE = Math.max(1, eL2Cluster);
                    for (int i = 0; i < eCount; i += stepE) {
                        BitSet b = new BitSet();
                        int limit = Math.min(eCount, i + stepE);
                        for (int c = i; c < limit; c++) {
                            b.set(c);
                        }
                        caches.add(new CacheDomain(2, l2Size, lineSize, b));
                    }
                    int stepP = Math.max(1, pL2Cluster);
                    for (int i = 0; i < pCount; i += stepP) {
                        BitSet b = new BitSet();
                        int limit = Math.min(pCount, i + stepP);
                        for (int c = i; c < limit; c++) {
                            b.set(eCount + c);
                        }
                        caches.add(new CacheDomain(2, l2Size, lineSize, b));
                    }
                } else {
                    BitSet all = new BitSet();
                    all.set(0, logicalCpus);
                    caches.add(new CacheDomain(2, l2Size, lineSize, all));
                }
            } else {
                BitSet all = new BitSet();
                all.set(0, logicalCpus);
                caches.add(new CacheDomain(2, l2Size, lineSize, all));
            }
        }

        if (l3Size > 0) {
            BitSet all = new BitSet();
            all.set(0, logicalCpus);
            caches.add(new CacheDomain(3, l3Size, lineSize, all));
        }

        return new TopologyInput("macos", cpus, caches);
    }

    private static void buildHomogeneousCpus(int logicalCpus, int physicalCpus, List<LogicalCpu> cpus) {
        int threadsPerCore = (physicalCpus > 0 && logicalCpus > physicalCpus) ? (logicalCpus / physicalCpus) : 1;
        for (int i = 0; i < logicalCpus; i++) {
            int coreIdx = i / threadsPerCore;
            cpus.add(new LogicalCpu(
                    i,
                    "macos:package:0",
                    "macos:die:0",
                    "macos:core:" + String.format("%08x", coreIdx),
                    CoreKind.UNKNOWN));
        }
    }

    private static TopologyInput fallbackInput(int count) {
        int c = Math.max(1, count);
        List<LogicalCpu> list = new ArrayList<>(c);
        for (int i = 0; i < c; i++) {
            list.add(new LogicalCpu(
                    i, "macos:package:0", "macos:die:0", "macos:core:" + String.format("%08x", i), CoreKind.UNKNOWN));
        }
        return new TopologyInput("macos", list, List.of());
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

    public TopologyModel getModel() {
        return model;
    }
}
