package euhedral.hardware_utils;

import euhedral.hardware_utils.SystemInfo.CoreInfo;
import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.hardware_utils.SystemInfo.SocketInfo;
import euhedral.hardware_utils.common.SystemUtilization.HardwareUtilization;
import euhedral.hardware_utils.common.UnmodifiableBitSet;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

public final class TopologyMapper {

    private final AtomicInteger globalVersion = new AtomicInteger(0);
    private final AtomicBoolean wip = new AtomicBoolean(false);

    private final BitSet allowedCpus;

    @Getter
    private volatile EffectiveSystemTopology effectiveTopology;

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
            for (int cpu = globalEffectiveCpus.nextSetBit(0); cpu >= 0;
                    cpu = globalEffectiveCpus.nextSetBit(cpu + 1)) {
                int core = SystemInfo.getCpuInfo(cpu).core();
                globalEffectiveCores.set(core);
            }

            BitSet globalEffectiveSockets = new BitSet(SystemInfo.MAX_SOCKET_ID);
            BitSet socketUpdated = new BitSet(SystemInfo.MAX_SOCKET_ID);

            EffectiveSystemTopology effectiveTopology = this.effectiveTopology;
            for (int cpu = 0; cpu < SystemInfo.CPU_COUNT; cpu++) {
                CpuInfo info = SystemInfo.getCpuInfo(cpu);
                EffectiveSocketTopology topology = null;

                if(SystemInfo.getSocketInfo(info.socket()) == null) {
                    globalEffectiveSockets.clear(info.socket());
                    continue;
                }

                if(info.socket() < effectiveTopology.socketTopologies.size()) {
                    topology = effectiveTopology.socketTopologies.get(info.socket());
                }

                if (topology == null) {
                    socketUpdated.set(info.socket());
                } else if (topology.effectiveCores.get(info.core()) != globalEffectiveCores.get(
                        info.core())) {
                    socketUpdated.set(info.socket());
                } else if (topology.effectiveCpus.get(cpu) != globalEffectiveCpus.get(cpu)) {
                    socketUpdated.set(info.socket());
                }

                if (globalEffectiveCores.get(info.core())) {
                    globalEffectiveSockets.set(info.socket());
                }
            }

            boolean globalUpdate = !effectiveTopology.effectiveSockets
                    .equals(globalEffectiveSockets);

            List<EffectiveSocketTopology> sTopologies = new ArrayList<>();
            effectiveTopology.socketTopologies.forEach(t -> sTopologies.add(null));

            for (int socket = socketUpdated.nextSetBit(0); socket >= 0;
                    socket = socketUpdated.nextSetBit(socket + 1)) {

                SocketInfo info = SystemInfo.getSocketInfo(socket);
                BitSet effectiveCores = info.getCoreSet();
                effectiveCores.and(globalEffectiveCores);

                BitSet effectiveCpus = info.getCpuSet();
                effectiveCpus.and(globalEffectiveCpus);

                EffectiveSocketTopology socketTopology = null;
                if(socket < effectiveTopology.socketTopologies.size()) {
                    socketTopology = effectiveTopology.socketTopologies.get(socket);
                }

                while(socket >= sTopologies.size()) {
                    sTopologies.add(null);
                }

                sTopologies.set(socket, new EffectiveSocketTopology(
                        socketTopology == null ? 1 : socketTopology.version + 1, socket,
                        new UnmodifiableBitSet(effectiveCores),
                        new UnmodifiableBitSet(effectiveCpus),
                        buildCoreToCpus(effectiveCpus)));
            }

            if (globalUpdate) {
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
