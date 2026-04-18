package euhedral.io.resource_monitoring;

import euhedral.io.resource_monitoring.SystemUtilization.HardwareUtilization;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

import euhedral.io.resource_monitoring.providers.WindowsCpuLocator;
import lombok.Getter;
import net.openhft.affinity.Affinity;
import net.openhft.affinity.AffinityLock;
import net.openhft.affinity.CpuLayout;

public class NumaMapper {

    public static final NumaMapper INSTANCE;

    static {
        INSTANCE = new NumaMapper();
    }

    public static OriginLocation locateMe() {
        int cpu;
        if (WindowsCpuLocator.LOADED) {
            cpu = WindowsCpuLocator.INSTANCE.getCpu();
        } else {
            cpu = Affinity.getCpu();
        }
        int core = INSTANCE.getPhysicalCore(cpu);
        int socket = INSTANCE.getSocket(core);
        return new OriginLocation(cpu, core, socket);
    }

    public int getPhysicalCore(int cpu) {
        int[] map = cpuToCore;
        if (cpu < 0 || cpu >= map.length) {
            return -1;
        }

        return map[cpu];
    }

    public int getSocket(int core) {
        int[] map = coreToNode;
        return (core >= 0 && core < map.length) ? map[core] : -1;
    }

    private final AtomicInteger globalTopologyVersion = new AtomicInteger(-1);
    private final CpuLayout layout = AffinityLock.cpuLayout();

    @Getter
    private final SystemTopology systemTopology;
    private final CpuInfo[] cpuInfo;
    private final int[] cpuToCore;
    private final int[] coreToNode;
    private final BitSet[] coreToCpus;
    private final BitSet[] nodeToCores;
    private final AtomicReferenceArray<SocketTopology> effectiveNodeTopologies;

    public NumaMapper() {
        int maxCoreId = -1;
        for (int i = 0; i < layout.cpus(); i++) {
            maxCoreId = Math.max(maxCoreId, layout.coreId(i));
        }
        int coreArraySize = Math.max(512, maxCoreId + 1);

        cpuInfo = new CpuInfo[512];
        cpuToCore = new int[512];
        coreToNode = new int[coreArraySize];
        coreToCpus = new BitSet[coreArraySize];

        int maxSocketId = -1;
        for (int i = 0; i < layout.cpus(); i++) {
            maxSocketId = Math.max(maxSocketId, layout.socketId(i));
        }
        int socketArraySize = Math.max(64, maxSocketId + 1);
        nodeToCores = new BitSet[socketArraySize];
        effectiveNodeTopologies = new AtomicReferenceArray<>(socketArraySize);
        systemTopology = new SystemTopology(new AtomicReference<>(new BitSet(socketArraySize)),
                new AtomicReference<>(new BitSet(coreArraySize)),
                new AtomicReference<>(new BitSet(cpuInfo.length)), effectiveNodeTopologies,
                globalTopologyVersion);

        init();
    }

    private void init() {
        int numCpus = layout.cpus();
        int numSockets = layout.sockets();

        for (int i = 0; i < numCpus; i++) {
            cpuInfo[i] = new CpuInfo(i, layout.coreId(i), layout.socketId(i));
        }

        for (int i = 0; i < numCpus; i++) {
            int core = layout.coreId(i);
            coreToCpus[core] = new BitSet(numCpus);
        }
        for (int i = 0; i < numSockets; i++) {
            nodeToCores[i] = new BitSet(coreToNode.length);
        }

        for (int i = 0; i < numCpus; i++) {
            int core = layout.coreId(i);
            int socketId = layout.socketId(i);
            cpuToCore[i] = core;
            coreToNode[core] = socketId;
            coreToCpus[core].set(i);
            nodeToCores[socketId].set(core);
        }

        for (int i = 0; i < numSockets; i++) {
            effectiveNodeTopologies.set(i, new SocketTopology(new AtomicInteger(-1),
                    new AtomicReference<>(new BitSet(coreToCpus.length)),
                    new AtomicReference<>(new BitSet(cpuInfo.length)),
                    new AtomicReference<>(new BitSet[coreToCpus.length])));
        }
    }

    public void update(HardwareUtilization utilization) {
        BitSet globalEffectiveCpus = utilization.globalEffectiveCpus();
        globalEffectiveCpus.and(getReservable());

        BitSet globalEffectiveCores = new BitSet(coreToCpus.length);
        for (int cpu = globalEffectiveCpus.nextSetBit(0); cpu >= 0;
                cpu = globalEffectiveCpus.nextSetBit(cpu + 1)) {
            int core = cpuToCore[cpu];
            globalEffectiveCores.set(core);
        }

        BitSet[] effectiveCoreToCpus = buildCoreToCpus(globalEffectiveCpus);

        BitSet globalEffectiveNodes = new BitSet(nodeToCores.length);
        BitSet nodeUpdated = new BitSet(nodeToCores.length);

        for (int cpu = 0; cpu < layout.cpus(); cpu++) {
            int core = cpuToCore[cpu];
            int node = coreToNode[core];
            SocketTopology topology = effectiveNodeTopologies.get(node);

            if (topology.effectiveCores.get().get(core) != globalEffectiveCores.get(core)) {
                nodeUpdated.set(node);
            }

            if (topology.effectiveCpus.get().get(cpu) != globalEffectiveCpus.get(cpu)) {
                nodeUpdated.set(node);
            }

            if (globalEffectiveCores.get(core)) {
                globalEffectiveNodes.set(node);
            }
        }

        boolean globalUpdate = !systemTopology.effectiveSockets.get().equals(globalEffectiveNodes);

        BitSet[] effectiveNodeToCores = buildNodeToCores(globalEffectiveCores);
        for (int node = nodeUpdated.nextSetBit(0); node >= 0;
                node = nodeUpdated.nextSetBit(node + 1)) {
            SocketTopology socketTopology = effectiveNodeTopologies.get(node);
            socketTopology.version.incrementAndGet();

            BitSet effectiveCores = effectiveNodeToCores[node];
            BitSet effectiveCpus = new BitSet(cpuInfo.length);
            for (int core = effectiveCores.nextSetBit(0); core >= 0;
                    core = effectiveCores.nextSetBit(core + 1)) {
                if (effectiveCoreToCpus[core] != null) {
                    effectiveCpus.or(effectiveCoreToCpus[core]);
                }
            }

            socketTopology.effectiveCores.set(effectiveCores);
            socketTopology.effectiveCpus.set(effectiveCpus);
            socketTopology.effectiveCoreToCpu.set(effectiveCoreToCpus);
        }

        if (globalUpdate) {
            this.globalTopologyVersion.incrementAndGet();
            this.systemTopology.effectiveSockets().set(globalEffectiveNodes);
            this.systemTopology.effectiveCores().set(globalEffectiveCores);
            this.systemTopology.effectiveCpus().set(globalEffectiveCpus);
        }
    }

    private BitSet[] buildCoreToCpus(BitSet cpus) {
        BitSet[] cores = new BitSet[coreToNode.length];

        for (int i = cpus.nextSetBit(0); i >= 0; i = cpus.nextSetBit(i + 1)) {
            int coreId = layout.coreId(i);
            if (cores[coreId] == null) {
                cores[coreId] = new BitSet(cpuToCore.length);
            }
            cores[coreId].set(i);
        }
        return cores;
    }

    private BitSet[] buildNodeToCores(BitSet effectiveCores) {
        BitSet[] nodeToCores = new BitSet[effectiveNodeTopologies.length()];

        for (int core = effectiveCores.nextSetBit(0); core >= 0;
                core = effectiveCores.nextSetBit(core + 1)) {
            int node = coreToNode[core];
            if (nodeToCores[node] == null) {
                nodeToCores[node] = new BitSet(coreToNode.length);
            }
            nodeToCores[node].set(core);
        }
        return nodeToCores;
    }

    public static BitSet getReservable() {
        String reservedAffinity = System.getProperty(AffinityLock.AFFINITY_RESERVED);
        if (AffinityLock.BASE_AFFINITY != null && (reservedAffinity == null
                || reservedAffinity.trim().isEmpty())) {
            BitSet reserverable = new BitSet(AffinityLock.PROCESSORS);
            reserverable.set(1, AffinityLock.PROCESSORS, true);
            reserverable.andNot(AffinityLock.BASE_AFFINITY);
            if (reserverable.isEmpty() && AffinityLock.PROCESSORS > 1) {
                reserverable.set(1, AffinityLock.PROCESSORS);
                return reserverable;
            }
            return reserverable;
        }

        reservedAffinity = reservedAffinity.trim();
        long[] longs = new long[1 + (reservedAffinity.length() - 1) / 16];
        int end = reservedAffinity.length();
        for (int i = 0; i < longs.length; i++) {
            int begin = Math.max(0, end - 16);
            longs[i] = Long.parseLong(reservedAffinity.substring(begin, end), 16);
            end = begin;
        }
        return BitSet.valueOf(longs);
    }

    public int getCpuCount() {
        return layout.cpus();
    }

    public int getCoreCount() {
        return coreToCpus.length;
    }

    public int getSocketCount() {
        return layout.sockets();
    }

    public SocketTopology getSocketTopology(int socketId) {
        if (socketId < 0 || socketId >= effectiveNodeTopologies.length()) {
            return null;
        }
        return effectiveNodeTopologies.get(socketId);
    }

    /**
     * Gets all available CPU cores for a socket. Available != Usable
     *
     * @param socketId Id of the socket.
     * @return All cores associated with the socket
     */
    public BitSet getAllCoresForSocket(int socketId) {
        BitSet[] map = nodeToCores;
        return (socketId >= 0 && socketId < map.length) ? map[socketId] : null;
    }

    public long getGlobalVersion() {
        return globalTopologyVersion.get();
    }

    public record CpuInfo(int cpuId, int coreId, int socketId) {

    }

    public record SocketTopology(AtomicInteger version, AtomicReference<BitSet> effectiveCores,
                                 AtomicReference<BitSet> effectiveCpus,
                                 AtomicReference<BitSet[]> effectiveCoreToCpu) {

    }

    public record SystemTopology(AtomicReference<BitSet> effectiveSockets,
                                 AtomicReference<BitSet> effectiveCores,
                                 AtomicReference<BitSet> effectiveCpus,
                                 AtomicReferenceArray<SocketTopology> socketTopologies,
                                 AtomicInteger globalVersion) {

    }

    public record OriginLocation(int cpu, int core, int socket) {

    }
}
