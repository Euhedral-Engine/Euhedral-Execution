package io.euhedral_execution.hardware_utils.windows;

import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;
import io.euhedral_execution.hardware_utils.windows.win32.CacheRelationship;
import io.euhedral_execution.hardware_utils.windows.win32.CacheRelationship.CacheType;
import io.euhedral_execution.hardware_utils.windows.win32.GroupAffinity;
import io.euhedral_execution.hardware_utils.windows.win32.ProcessorRelationship;
import io.euhedral_execution.hardware_utils.windows.win32.SystemLogicalProcessorInformation;
import it.unimi.dsi.fastutil.ints.Int2BooleanArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WindowsSystemLayout {

    public static final WindowsSystemLayout INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(
            WindowsSystemLayout.class));

    static {
        JNIClassLoader.load();

        WindowsSystemLayout layout = null;
        if (OSName.isWindows()) {
            layout = new WindowsSystemLayout();
        }
        INSTANCE = layout;
    }

    private static native byte[] getRawTopologyInfo();
    private final Int2ObjectArrayMap<CpuCacheLayout> cpuCache = new Int2ObjectArrayMap<>();
    private final Int2ObjectArrayMap<CpuInfo> cpuInfo = new Int2ObjectArrayMap<>();
    private final Int2ObjectArrayMap<CoreInfo> coreInfo = new Int2ObjectArrayMap<>();
    private final Int2ObjectArrayMap<SocketInfo> socketInfo = new Int2ObjectArrayMap<>();
    private Int2ObjectArrayMap<BitSet> cpuToCore = new Int2ObjectArrayMap<>();
    private Int2ObjectArrayMap<BitSet> cpuToSocket = new Int2ObjectArrayMap<>();
    private Int2ObjectArrayMap<BitSet> coreToCpu = new Int2ObjectArrayMap<>();
    private Int2BooleanArrayMap isPCore = new Int2BooleanArrayMap();
    private Int2ObjectArrayMap<long[][]> cpuCacheVals = new Int2ObjectArrayMap<>();

    private WindowsSystemLayout() {
        init(getRawTopologyInfo());
    }

    private void init(byte[] rawData) {
        if (rawData == null) {
            return;
        }

        List<SystemLogicalProcessorInformation> info = SystemLogicalProcessorInformation.parse(
                rawData);

        int[] socketId = {0};
        int[] coreId = {0};
        for (var i : info) {
            switch (i.relationship) {
                case PROCESSOR_PACKAGE -> processorPackage(socketId[0]++,
                        ((ProcessorRelationship) i).groupAffinities);
                case PROCESSOR_CORE -> processorCore(coreId[0]++, (ProcessorRelationship) i);
                case CACHE -> {
                    CacheRelationship cr = (CacheRelationship) i;
                    if (cr.type == CacheType.INSTRUCTION) {
                        continue;
                    }
                    for (GroupAffinity affinity : cr.groupAffinities) {
                        processMask(affinity.mask(), affinity.group(), cpuId -> {
                            long[][] cache = cpuCacheVals.computeIfAbsent(cpuId,
                                    k -> new long[3][4]);
                            cache[cr.level - 1][0] = cr.cacheSizeBytes;
                            cache[cr.level - 1][1] = affinity.mask();
                            cache[cr.level - 1][2] = cr.lineSize;
                            cache[cr.level - 1][3] = cr.groupAffinities.size();
                        });
                    }
                }
                default -> {
                    // Do nothing with other relation types.
                }
            }
        }

        buildMaps();

        this.cpuToCore = null;
        this.coreToCpu = null;
        this.cpuToSocket = null;
        this.cpuCacheVals = null;
        this.isPCore = null;
    }

    private void processorPackage(int socketId, List<GroupAffinity> group) {
        for (GroupAffinity affinity : group) {
            processMask(affinity.mask(), affinity.group(), cpuId -> {
                BitSet c2s = cpuToSocket.computeIfAbsent(cpuId, k -> new BitSet());
                c2s.set(socketId);
            });
        }
    }

    private void processorCore(int coreId, ProcessorRelationship pr) {
        for (GroupAffinity affinity : pr.groupAffinities) {
            processMask(affinity.mask(), affinity.group(), cpuId -> {
                BitSet cpu2core = cpuToCore.computeIfAbsent(cpuId, k -> new BitSet());
                cpu2core.set(coreId);

                this.isPCore.compute(cpuId, (k, v) -> Boolean.TRUE.equals(v) || pr.pCore);

                BitSet core2cpu = this.coreToCpu.computeIfAbsent(coreId, k -> new BitSet());
                core2cpu.set(cpuId);
            });
        }
    }

    private void processMask(long mask, int group, IntConsumer action) {
        int bits = 0;
        while (mask > 0) {
            int curr = Long.numberOfTrailingZeros(mask) + 1;
            bits += curr;
            int shifted = (group * 64) + (bits - 1);

            action.accept(shifted);
            mask >>>= curr;
        }
    }

    private void buildMaps() {
        LOGGER.trace("Detected {} cpus", this.cpuToCore.size());
        LOGGER.trace("Detected {} cores", this.coreToCpu.size());
        for (int cpu : this.cpuToCore.keySet()) {
            int core = this.cpuToCore.get(cpu).nextSetBit(0);
            int socket = this.cpuToSocket.get(cpu).nextSetBit(0);
            this.cpuInfo.put(cpu, new CpuInfo(cpu, core, socket));
        }

        Int2ObjectArrayMap<BitSet> socket2cpu = new Int2ObjectArrayMap<>();
        Int2ObjectArrayMap<BitSet> socket2core = new Int2ObjectArrayMap<>();

        for (int core : this.coreToCpu.keySet()) {
            BitSet cpus = this.coreToCpu.get(core);
            int socket = this.cpuToSocket.get(cpus.nextSetBit(0)).nextSetBit(0);
            boolean pCore = this.isPCore.get(cpus.nextSetBit(0));

            this.coreInfo.put(core, new CoreInfo(SystemInfo.toHexMask(cpus), pCore, core, socket));
            BitSet s2cpu = socket2cpu.computeIfAbsent(socket, k -> new BitSet());
            BitSet s2core = socket2core.computeIfAbsent(socket, k -> new BitSet());
            s2cpu.or(cpus);
            s2core.set(core);
        }

        for (int socket : socket2cpu.keySet()) {
            this.socketInfo.put(socket,
                    new SocketInfo(
                            SystemInfo.toHexMask(socket2cpu.get(socket)),
                            SystemInfo.toHexMask(socket2core.get(socket)),
                            socket
                    ));
        }
        LOGGER.trace("Detected {} sockets", this.socketInfo.size());

        for (int cpu : this.cpuCacheVals.keySet()) {
            long[][] vals = this.cpuCacheVals.get(cpu);
            int socket = this.cpuToSocket.get(cpu).nextSetBit(0);
            int cpusInSocket = socket2cpu.get(socket).cardinality();

            BitSet cpuSet = new BitSet();
            BitSet coreSet = new BitSet();
            processMask(vals[0][1], (int) vals[0][3], cpuSet::set);
            processMask(vals[1][1], (int) vals[0][3], coreSet::set);

            String maskL1 = SystemInfo.toHexMask(cpuSet);
            String maskL2 = SystemInfo.toHexMask(coreSet);
            String maskL3 = SystemInfo.toHexMask(socket2cpu.get(socket));
            this.cpuCache.put(cpu, new CpuCacheLayout(cpu,
                    vals[0][0], vals[1][0], vals[2][0],
                    Long.bitCount(vals[0][1]), Long.bitCount(vals[1][1]), cpusInSocket,
                    maskL1, maskL2, maskL3,
                    (int) vals[0][2]
            ));
        }
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
}
