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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EffectiveTopology {

    private static final Logger LOGGER = LoggerFactory.getLogger(EffectiveTopology.class);

    private static volatile EffectiveSystemTopology EFFECTIVE_TOPOLOGY;
    private static final AtomicInteger GLOBAL_VERSION = new AtomicInteger(-1);

    static {
        try {
            EFFECTIVE_TOPOLOGY = new EffectiveSystemTopology(
                    new UnmodifiableBitSet(new BitSet()),
                    new UnmodifiableBitSet(new BitSet()),
                    new UnmodifiableBitSet(new BitSet()),
                    List.of(),
                    -1);
        } catch (Exception e) {
            LOGGER.error("FATAL: Failed to instantiate the EffectiveTopology.", e);
        }
    }

    private static final AtomicBoolean wip = new AtomicBoolean(false);

    public static EffectiveSystemTopology getEffectiveTopology() {
        return EFFECTIVE_TOPOLOGY;
    }

    public static EffectiveSocketTopology getEffectiveSocketTopology(int socketId) {
        EffectiveSystemTopology topology = EFFECTIVE_TOPOLOGY;
        if (socketId < 0 || socketId >= topology.socketTopologies.size()) {
            return null;
        }
        return topology.socketTopologies.get(socketId);
    }

    public static void update(@NonNull HardwareUtilization utilization) throws Exception {
        if(!wip.compareAndSet(false, true)) {
            return;
        }

        try {
            BitSet globalEffectiveCpus = utilization.globalEffectiveCpus();
            CoreInfo coreInfo = SystemInfo.getCoreInfo(0);
            if (coreInfo != null) {
                globalEffectiveCpus.andNot(coreInfo.getCpuSet());
            }

            BitSet globalEffectiveCores = new BitSet(SystemInfo.MAX_CORE_ID);
            for (int cpu = globalEffectiveCpus.nextSetBit(0); cpu >= 0;
                    cpu = globalEffectiveCpus.nextSetBit(cpu + 1)) {
                int core = SystemInfo.getCpuInfo(cpu).core();
                globalEffectiveCores.set(core);
            }

            BitSet globalEffectiveSockets = new BitSet(SystemInfo.MAX_SOCKET_ID);
            BitSet socketUpdated = new BitSet(SystemInfo.MAX_SOCKET_ID);

            EffectiveSystemTopology effectiveTopology = EFFECTIVE_TOPOLOGY;
            for (int cpu = 0; cpu < SystemInfo.CPU_COUNT; cpu++) {
                CpuInfo info = SystemInfo.getCpuInfo(cpu);
                EffectiveSocketTopology topology = effectiveTopology.socketTopologies.get(
                        info.socket());

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

                EffectiveSocketTopology socketTopology = effectiveTopology.socketTopologies.get(
                        socket);
                sTopologies.set(socket, new EffectiveSocketTopology(
                        socketTopology == null ? 0 : socketTopology.version + 1, socket,
                        new UnmodifiableBitSet(globalEffectiveCpus),
                        new UnmodifiableBitSet(effectiveCpus),
                        buildCoreToCpus(effectiveCpus)));
            }

            if (globalUpdate) {
                int version = GLOBAL_VERSION.incrementAndGet();
                EFFECTIVE_TOPOLOGY = new EffectiveSystemTopology(
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

    private static List<BitSet> buildCoreToCpus(BitSet cpus) throws Exception {
        List<BitSet> cores = new ArrayList<>(SystemInfo.MAX_CORE_ID);

        for (int i = cpus.nextSetBit(0); i >= 0; i = cpus.nextSetBit(i + 1)) {
            int coreId = SystemInfo.getCpuInfo(i).core();
            while (cores.size() < coreId) {
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

    public static int getGlobalVersion() {
        return GLOBAL_VERSION.get();
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
