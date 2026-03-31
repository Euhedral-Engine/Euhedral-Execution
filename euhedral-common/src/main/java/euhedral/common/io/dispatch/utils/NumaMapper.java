package euhedral.common.io.dispatch.utils;

import euhedral.common.io.dispatch.utils.SystemUtilization.HardwareUtilization;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import lombok.Getter;
import net.openhft.affinity.AffinityLock;
import net.openhft.affinity.CpuLayout;

public class NumaMapper {
    public static final NumaMapper INSTANCE;

    static {
        INSTANCE = new NumaMapper();
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

    private final AtomicReferenceArray<NodeTopology> effectiveNodeTopologies;

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
                new AtomicReference<>(new BitSet(cpuInfo.length)),
                effectiveNodeTopologies, globalTopologyVersion);

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
            effectiveNodeTopologies.set(i,
                    new NodeTopology(new AtomicInteger(-1),
                            new AtomicReference<>(new BitSet(coreToCpus.length)),
                            new AtomicReference<>(new BitSet(cpuInfo.length)),
                            new AtomicReference<>(new BitSet[coreToCpus.length])));
        }
    }

    public void update(HardwareUtilization utilization) {
        BitSet globalEffectiveCpus = utilization.globalEffectiveCpus();
        BitSet globalEffectiveCores = new BitSet(coreToCpus.length);
        for (int cpu = globalEffectiveCpus.nextSetBit(0); cpu >= 0;
                cpu = globalEffectiveCpus.nextSetBit(cpu + 1)) {
            int core = cpuToCore[cpu];
            globalEffectiveCores.set(core);
        }

        BitSet[] effectiveCoreToCpus = buildCoreToCpus(globalEffectiveCpus);
        tryExcludeCore0(globalEffectiveCores, globalEffectiveCpus, effectiveCoreToCpus[0]);

        BitSet globalEffectiveNodes = new BitSet(nodeToCores.length);
        BitSet nodeUpdated = new BitSet(nodeToCores.length);

        for (int cpu = 0; cpu < layout.cpus(); cpu++) {
            int core = cpuToCore[cpu];
            int node = coreToNode[core];
            NodeTopology topology = effectiveNodeTopologies.get(node);

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

        boolean globalUpdate = !systemTopology.effectiveNodes.get().equals(globalEffectiveNodes);

        BitSet[] effectiveNodeToCores = buildNodeToCores(globalEffectiveCores);
        for (int node = nodeUpdated.nextSetBit(0); node >= 0;
                node = nodeUpdated.nextSetBit(node + 1)) {
            NodeTopology nodeTopology = effectiveNodeTopologies.get(node);
            nodeTopology.version.incrementAndGet();

            BitSet effectiveCores = effectiveNodeToCores[node];
            BitSet effectiveCpus = new BitSet(cpuInfo.length);
            for (int core = effectiveCores.nextSetBit(0); core >= 0;
                    core = effectiveCores.nextSetBit(core + 1)) {
                if (effectiveCoreToCpus[core] != null) {
                    effectiveCpus.or(effectiveCoreToCpus[core]);
                }
            }

            nodeTopology.effectiveCores.set(effectiveCores);
            nodeTopology.effectiveCpus.set(effectiveCpus);
            nodeTopology.effectiveCoreToCpu.set(effectiveCoreToCpus);
        }

        if (globalUpdate) {
            this.globalTopologyVersion.incrementAndGet();
            this.systemTopology.effectiveNodes().set(globalEffectiveNodes);
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

    private void tryExcludeCore0(BitSet effectiveCores, BitSet effectiveCpus, BitSet core0ToCpus) {
        if (!effectiveCores.get(0) || effectiveCores.cardinality() < 2) {
            return;
        }


        effectiveCores.clear(0);
        if (core0ToCpus != null) {
            effectiveCpus.andNot(core0ToCpus);
            core0ToCpus.clear();
        }
    }

    public int getPhysicalCore(int cpu) {
        return cpuToCore[cpu];
    }

    public int getNodeId(int core) {
        int[] map = coreToNode;
        return (core >= 0 && core < map.length) ? map[core] : -1;
    }

    public int getNodeCount() {
        return layout.sockets();
    }

    public int getCpuCount() {
        return layout.cpus();
    }

    public NodeTopology getNodeTopology(int nodeId) {
        if (nodeId < 0 || nodeId >= effectiveNodeTopologies.length()) {
            return null;
        }
        return effectiveNodeTopologies.get(nodeId);
    }

    /**
     * Gets all available CPU cores for a node. Available != Usable
     *
     * @param nodeId Id of the node.
     * @return All cores associated with the node
     */
    public BitSet getAllCoresForNode(int nodeId) {
        BitSet[] map = nodeToCores;
        return (nodeId >= 0 && nodeId < map.length) ? map[nodeId] : null;
    }

    public long getGlobalVersion() {
        return globalTopologyVersion.get();
    }

    public record CpuInfo(int cpuId, int coreId, int socketId) {

    }

    public record SystemTopology(AtomicReference<BitSet> effectiveNodes,
                                 AtomicReference<BitSet> effectiveCores,
                                 AtomicReference<BitSet> effectiveCpus,
                                 AtomicReferenceArray<NodeTopology> nodeTopologies,
                                 AtomicInteger globalVersion) {

    }

    public record NodeTopology(AtomicInteger version, AtomicReference<BitSet> effectiveCores,
                               AtomicReference<BitSet> effectiveCpus,
                               AtomicReference<BitSet[]> effectiveCoreToCpu) {

    }
}
