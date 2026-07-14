package io.euhedral_execution.hardware_utils.windows;

import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;
import io.euhedral_execution.hardware_utils.windows.win32.CacheRelationship;
import io.euhedral_execution.hardware_utils.windows.win32.CacheRelationship.CacheType;
import io.euhedral_execution.hardware_utils.windows.win32.GroupAffinity;
import io.euhedral_execution.hardware_utils.windows.win32.ProcessorRelationship;
import io.euhedral_execution.hardware_utils.windows.win32.Relationship;
import io.euhedral_execution.hardware_utils.windows.win32.SystemLogicalProcessorInformation;
import it.unimi.dsi.fastutil.ints.Int2BooleanArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

public final class WindowsSystemLayout {

    public static final WindowsSystemLayout INSTANCE;

    static {
        JNIClassLoader.load();

        WindowsSystemLayout layout = null;
        if (OSName.isWindows()) {
            layout = new WindowsSystemLayout();
        }
        INSTANCE = layout;
    }

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

        List<SystemLogicalProcessorInformation> info = SystemLogicalProcessorInformation.parse(rawData);

        int[] socketId = {0};
        int[] coreId = {0};
        for(var i : info) {
            if(i instanceof ProcessorRelationship pr) {
                if(pr.relationship == Relationship.PROCESSOR_PACKAGE) {
                    for(GroupAffinity affinity : pr.groupAffinities) {
                        processMask(affinity.mask(), affinity.group(), cpuId -> {
                            BitSet c2s = cpuToSocket.computeIfAbsent(cpuId, k -> new BitSet());
                            c2s.set(socketId[0]);
                        });
                    }
                    socketId[0]++;
                } else if(pr.relationship == Relationship.PROCESSOR_CORE) {
                    for(GroupAffinity affinity : pr.groupAffinities) {
                        processMask(affinity.mask(), affinity.group(), cpuId -> {
                            BitSet cpu2core = cpuToCore.computeIfAbsent(cpuId, k -> new BitSet());
                            cpu2core.set(coreId[0]);

                            isPCore.compute(cpuId, (k, v) -> Boolean.TRUE.equals(v) | pr.pCore);

                            BitSet core2cpu = coreToCpu.computeIfAbsent(coreId[0], k -> new BitSet());
                            core2cpu.set(cpuId);
                        });
                    }
                    coreId[0]++;
                }
            } else if(i instanceof CacheRelationship cr) {
                if(cr.type == CacheType.INSTRUCTION) {
                    continue;
                }
                for(GroupAffinity affinity : cr.groupAffinities) {
                    processMask(affinity.mask(), affinity.group(), cpuId -> {
                        long[][] cache = cpuCacheVals.computeIfAbsent(cpuId, k -> new long[3][4]);
                        cache[cr.level - 1][0] = cr.cacheSizeBytes;
                        cache[cr.level - 1][1] = affinity.mask();
                        cache[cr.level - 1][2] = cr.lineSize;
                        cache[cr.level - 1][3] = cr.groupAffinities.size();
                    });
                }
            }
        }

        buildMaps();

        cpuToCore = null;
        coreToCpu = null;
        cpuToSocket = null;
        cpuCacheVals = null;
        isPCore = null;
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
        for (int cpu : cpuToCore.keySet()) {
            int core = cpuToCore.get(cpu).nextSetBit(0);
            int socket = cpuToSocket.get(cpu).nextSetBit(0);
            cpuInfo.put(cpu, new CpuInfo(cpu, core, socket));
        }

        Int2ObjectArrayMap<BitSet> socket2cpu = new Int2ObjectArrayMap<>();
        Int2ObjectArrayMap<BitSet> socket2core = new Int2ObjectArrayMap<>();

        for (int core : coreToCpu.keySet()) {
            BitSet cpus = coreToCpu.get(core);
            int socket = cpuToSocket.get(cpus.nextSetBit(0)).nextSetBit(0);
            boolean pCore = isPCore.get(cpus.nextSetBit(0));

            coreInfo.put(core, new CoreInfo(SystemInfo.toHexMask(cpus), pCore, core, socket));
            BitSet s2cpu = socket2cpu.computeIfAbsent(socket, k -> new BitSet());
            BitSet s2core = socket2core.computeIfAbsent(socket, k -> new BitSet());
            s2cpu.or(cpus);
            s2core.set(core);
        }

        for (int socket : socket2cpu.keySet()) {
            socketInfo.put(socket,
                    new SocketInfo(
                            SystemInfo.toHexMask(socket2cpu.get(socket)),
                            SystemInfo.toHexMask(socket2core.get(socket)),
                            socket
                    ));
        }

        for(int cpu : cpuCacheVals.keySet()) {
            long[][] vals = cpuCacheVals.get(cpu);
            int socket = cpuToSocket.get(cpu).nextSetBit(0);
            int cpusInSocket = socket2cpu.get(socket).cardinality();

            BitSet cpuSet = new BitSet();
            BitSet coreSet = new BitSet();
            processMask(vals[0][1], (int) vals[0][3], cpuSet::set);
            processMask(vals[1][1], (int) vals[0][3], coreSet::set);

            String maskL1 = SystemInfo.toHexMask(cpuSet);
            String maskL2 = SystemInfo.toHexMask(coreSet);
            String maskL3 = SystemInfo.toHexMask(socket2cpu.get(socket));
            cpuCache.put(cpu, new CpuCacheLayout(cpu,
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

    private static native byte[] getRawTopologyInfo();
}
