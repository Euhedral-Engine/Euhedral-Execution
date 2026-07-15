package io.euhedral_execution.hardware_utils;

import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

@SuppressWarnings("unused")
public final class TopologyMapper {

    private final AtomicInteger globalVersion = new AtomicInteger(0);
    private final AtomicBoolean wip = new AtomicBoolean(false);

    private final BitSet allowedCpus;

    @Getter
    private EffectiveSystemTopology effectiveTopology;

    public TopologyMapper() {
        this(SystemInfo.getCpuSet());
    }

    public TopologyMapper(BitSet allowedCpus) {
        this.allowedCpus = allowedCpus;
        this.effectiveTopology = new EffectiveSystemTopology(
                new UnmodifiableBitSet(new BitSet()),
                new UnmodifiableBitSet(new BitSet()),
                new UnmodifiableBitSet(new BitSet()),
                List.of(),
                -1);
    }

    public EffectiveSocketTopology getEffectiveSocketTopology(int socketId) {
        EffectiveSystemTopology topology = effectiveTopology;
        if (socketId < 0 || socketId >= topology.socketTopologies.size()) {
            return null;
        }
        return topology.socketTopologies.get(socketId);
    }

    public void update(@NonNull HardwareUtilization utilization) {
        if(!wip.compareAndSet(false, true)) {
            return;
        }

        try {
            BitSet globalEffectiveCpus = (BitSet) utilization.globalEffectiveCpus().clone();
            CoreInfo coreInfo = SystemInfo.getCoreInfo(0);
            if (coreInfo != null) {
                globalEffectiveCpus.andNot(coreInfo.getCpuSet());
            }
            globalEffectiveCpus.and(this.allowedCpus);


            BitSet globalEffectiveCores = new BitSet(SystemInfo.MAX_CORE_ID);
            BitSet globalEffectiveSockets = new BitSet(SystemInfo.MAX_SOCKET_ID);
            buildGlobalEffective(globalEffectiveCpus, globalEffectiveCores, globalEffectiveSockets);

            EffectiveSystemTopology effectiveTopology = this.effectiveTopology;

            BitSet socketUpdated = new BitSet(SystemInfo.MAX_SOCKET_ID);
            socketUpdated.or(globalEffectiveSockets);
            socketUpdated.xor(effectiveTopology.effectiveSockets);

            if (socketUpdated.cardinality() > 0) {
                List<EffectiveSocketTopology> sTopologies = buildSocketTopologies(globalEffectiveCpus, globalEffectiveCores, socketUpdated);

                int version = globalVersion.incrementAndGet();
                this.effectiveTopology = new EffectiveSystemTopology(
                        new UnmodifiableBitSet(globalEffectiveSockets),
                        new UnmodifiableBitSet(globalEffectiveCores),
                        new UnmodifiableBitSet(globalEffectiveCpus),
                        Collections.unmodifiableList(sTopologies),
                        version
                );
            }
        } finally {
            wip.set(false);
        }
    }

    private void buildGlobalEffective(BitSet effectiveCpus, BitSet effectiveCores, BitSet effectiveSockets) {
        for (int cpu = effectiveCpus.nextSetBit(0); cpu >= 0; cpu = effectiveCpus.nextSetBit(cpu + 1)) {
            CpuInfo info = SystemInfo.getCpuInfo(cpu);
            if(info != null) {
                effectiveCores.set(info.core());
                effectiveSockets.set(info.socket());
            }
        }
    }

    private List<EffectiveSocketTopology> buildSocketTopologies(BitSet globalEffectiveCpus, BitSet globalEffectiveCores, BitSet globalEffectiveSockets) {
        EffectiveSystemTopology effectiveTopology = this.effectiveTopology;

        List<EffectiveSocketTopology> sTopologies = new ArrayList<>();
        effectiveTopology.socketTopologies.forEach(t -> sTopologies.add(null));

        for(int socket = 0; socket <= SystemInfo.getMaxSocketId(); socket++) {
            if(!globalEffectiveSockets.get(socket)) {
                sTopologies.add(null);
                continue;
            }

            SocketInfo info = SystemInfo.getSocketInfo(socket);
            BitSet effectiveCores = info.getCoreSet();
            effectiveCores.and(globalEffectiveCores);

            BitSet effectiveCpus = info.getCpuSet();
            effectiveCpus.and(globalEffectiveCpus);

            EffectiveSocketTopology socketTopology = effectiveTopology.socketTopologies.size() > socket ? effectiveTopology.socketTopologies.get(socket) : null;
            sTopologies.add(socket, new EffectiveSocketTopology(
                    socketTopology == null ? 1 : socketTopology.version + 1, socket,
                    new UnmodifiableBitSet(effectiveCores),
                    new UnmodifiableBitSet(effectiveCpus),
                    buildCoreToCpus(effectiveCpus)));
        }
        return sTopologies;
    }

    private List<BitSet> buildCoreToCpus(BitSet cpus) {
        List<BitSet> cores = new ArrayList<>(SystemInfo.MAX_CORE_ID);

        for (int i = cpus.nextSetBit(0); i >= 0; i = cpus.nextSetBit(i + 1)) {
            int coreId = SystemInfo.getCpuInfo(i).core();
            while (cores.size() <= coreId) {
                cores.add(null);
            }
            if (cores.get(coreId) == null) {
                cores.set(coreId, new BitSet(SystemInfo.CPU_COUNT));
            }
            cores.get(coreId).set(i);
        }

        for (int i = 0; i < cores.size(); i++) {
            if (cores.get(i) != null) {
                cores.set(i, new UnmodifiableBitSet(cores.get(i)));
            }
        }
        return Collections.unmodifiableList(cores);
    }

    public int getGlobalVersion() {
        return globalVersion.get();
    }

    public record EffectiveSocketTopology(int version, int socketId, BitSet effectiveCores,
                                          BitSet effectiveCpus,
                                          List<BitSet> effectiveCoreToCpu) {

    }

    public record EffectiveSystemTopology(BitSet effectiveSockets,
                                          BitSet effectiveCores,
                                          BitSet effectiveCpus,
                                          List<EffectiveSocketTopology> socketTopologies,
                                          int globalVersion) {

    }
}
