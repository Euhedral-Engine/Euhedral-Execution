package io.euhedral_execution.hardware_utils;

import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.SocketInfo;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.internal.topology.TopologyModel;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import org.jspecify.annotations.NonNull;

@SuppressWarnings("unused")
public final class TopologyMapper {

    private final TopologyModel topologyModel;
    private final UnmodifiableBitSet allowedCpus;
    private final int[] socketVersions;
    private final AtomicLong submissionSequence = new AtomicLong();
    private final AtomicReference<PendingRequest> pending = new AtomicReference<>();
    private final AtomicBoolean drainOwner = new AtomicBoolean();
    // The graph is completely frozen before this volatile write. Reader methods perform one
    // volatile read, which publishes every final value reachable from the selected graph.
    @Getter
    private volatile EffectiveSystemTopology effectiveTopology;
    public TopologyMapper() {
        this(SystemInfo.topologyModel(), SystemInfo.topologyModel().cpuSet());
    }
    public TopologyMapper(BitSet allowedCpus) {
        this(SystemInfo.topologyModel(), allowedCpus);
    }
    TopologyMapper(TopologyModel topologyModel, BitSet allowedCpus) {
        this.topologyModel = Objects.requireNonNull(topologyModel, "topologyModel");
        BitSet ownedAllowed =
                (BitSet) Objects.requireNonNull(allowedCpus, "allowedCpus").clone();
        ownedAllowed.and(topologyModel.cpuSet());
        this.allowedCpus = new UnmodifiableBitSet(ownedAllowed);
        this.socketVersions = new int[topologyModel.maxSocketId() + 1];
        this.effectiveTopology = new EffectiveSystemTopology(
                new BitSet(), new BitSet(), new BitSet(), fixedNullList(topologyModel.maxSocketId() + 1), -1);
    }

    private static boolean sameMembership(EffectiveSocketTopology first, EffectiveSocketTopology second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.effectiveCores.equals(second.effectiveCores) && first.effectiveCpus.equals(second.effectiveCpus);
    }

    private static int checkedIncrement(int value, String label) {
        if (value == Integer.MAX_VALUE) {
            throw new IllegalStateException(label + " topology version overflow");
        }
        return value + 1;
    }

    private static <T> List<T> fixedNullList(int size) {
        return Collections.unmodifiableList(new ArrayList<>(Collections.nCopies(size, null)));
    }

    public EffectiveSocketTopology getEffectiveSocketTopology(int socketId) {
        EffectiveSystemTopology topology = effectiveTopology;
        if (socketId < 0 || socketId >= topology.socketTopologies.size()) {
            return null;
        }
        return topology.socketTopologies.get(socketId);
    }

    public int getGlobalVersion() {
        return effectiveTopology.globalVersion;
    }

    public void update(@NonNull HardwareUtilization utilization) {
        Objects.requireNonNull(utilization, "utilization");
        BitSet membership = (BitSet) utilization.globalEffectiveCpus().clone();
        membership.and(topologyModel.cpuSet());
        membership.and(allowedCpus);
        reserveCoreZero(membership);

        long sequence = nextSequence();
        PendingRequest request = new PendingRequest(sequence, new UnmodifiableBitSet(membership));
        installGreatest(request);
        if (drainOwner.compareAndSet(false, true)) {
            drain();
        }
    }

    private void reserveCoreZero(BitSet membership) {
        CoreInfo coreZero = topologyModel.coreInfo().get(0);
        if (coreZero == null || membership.isEmpty()) {
            return;
        }
        BitSet zeroCpus = coreZero.getCpuSet();
        BitSet alternatives = (BitSet) membership.clone();
        alternatives.andNot(zeroCpus);
        if (!alternatives.isEmpty()) {
            membership.andNot(zeroCpus);
        }
    }

    private void installGreatest(PendingRequest request) {
        PendingRequest observed = pending.get();
        while (observed == null || observed.sequence < request.sequence) {
            if (pending.compareAndSet(observed, request)) {
                return;
            }
            observed = pending.get();
        }
    }

    private long nextSequence() {
        long observed = submissionSequence.get();
        while (true) {
            if (observed == Long.MAX_VALUE) {
                throw new IllegalStateException("topology submission sequence overflow");
            }
            long next = observed + 1;
            if (submissionSequence.compareAndSet(observed, next)) {
                return next;
            }
            observed = submissionSequence.get();
        }
    }

    private void drain() {
        RuntimeException firstFailure = null;
        while (true) {
            PendingRequest request = pending.getAndSet(null);
            if (request != null) {
                try {
                    publishIfChanged(request.membership);
                } catch (RuntimeException failure) {
                    if (firstFailure == null) {
                        firstFailure = failure;
                    }
                }
                continue;
            }

            drainOwner.set(false);
            if (pending.get() != null && drainOwner.compareAndSet(false, true)) {
                continue;
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
            return;
        }
    }

    private void publishIfChanged(BitSet effectiveCpus) {
        BitSet effectiveCores = new BitSet(topologyModel.coreCount());
        BitSet effectiveSockets = new BitSet(topologyModel.socketCount());
        for (int cpu = effectiveCpus.nextSetBit(0); cpu >= 0; cpu = effectiveCpus.nextSetBit(cpu + 1)) {
            CpuInfo info = topologyModel.cpuInfo().get(cpu);
            if (info == null) {
                throw new IllegalStateException("No topology entry for active CPU " + cpu);
            }
            effectiveCores.set(info.core());
            effectiveSockets.set(info.socket());
        }

        EffectiveSystemTopology previous = effectiveTopology;
        if (effectiveCpus.equals(previous.effectiveCpus)
                && effectiveCores.equals(previous.effectiveCores)
                && effectiveSockets.equals(previous.effectiveSockets)) {
            return;
        }

        int nextGlobalVersion = previous.globalVersion == -1 ? 1 : checkedIncrement(previous.globalVersion, "global");
        List<EffectiveSocketTopology> nextSockets =
                new ArrayList<>(Collections.nCopies(topologyModel.maxSocketId() + 1, null));
        int[] nextSocketVersions = socketVersions.clone();

        for (int socketId = 0; socketId <= topologyModel.maxSocketId(); socketId++) {
            EffectiveSocketTopology priorSocket = previous.socketTopologies.get(socketId);
            EffectiveSocketTopology candidate =
                    buildSocketTopology(socketId, effectiveCpus, effectiveCores, effectiveSockets.get(socketId), 0);
            boolean changed = !sameMembership(priorSocket, candidate);
            if (changed) {
                nextSocketVersions[socketId] = checkedIncrement(nextSocketVersions[socketId], "socket " + socketId);
            }
            if (candidate != null) {
                nextSockets.set(
                        socketId,
                        new EffectiveSocketTopology(
                                nextSocketVersions[socketId],
                                candidate.socketId,
                                candidate.effectiveCores,
                                candidate.effectiveCpus,
                                candidate.effectiveCoreToCpu));
            }
        }

        EffectiveSystemTopology next = new EffectiveSystemTopology(
                effectiveSockets, effectiveCores, effectiveCpus, nextSockets, nextGlobalVersion);
        System.arraycopy(nextSocketVersions, 0, socketVersions, 0, socketVersions.length);
        effectiveTopology = next;
    }

    private EffectiveSocketTopology buildSocketTopology(
            int socketId, BitSet globalCpus, BitSet globalCores, boolean active, int version) {
        if (!active) {
            return null;
        }
        SocketInfo info = topologyModel.socketInfo().get(socketId);
        if (info == null) {
            throw new IllegalStateException("No topology entry for active socket " + socketId);
        }
        BitSet cpus = info.getCpuSet();
        cpus.and(globalCpus);
        BitSet cores = info.getCoreSet();
        cores.and(globalCores);
        return new EffectiveSocketTopology(version, socketId, cores, cpus, buildCoreToCpus(cpus));
    }

    private List<BitSet> buildCoreToCpus(BitSet cpus) {
        List<BitSet> cores = new ArrayList<>(Collections.nCopies(topologyModel.maxCoreId() + 1, null));
        for (int cpu = cpus.nextSetBit(0); cpu >= 0; cpu = cpus.nextSetBit(cpu + 1)) {
            CpuInfo info = topologyModel.cpuInfo().get(cpu);
            BitSet coreCpus = cores.get(info.core());
            if (coreCpus == null) {
                coreCpus = new BitSet(topologyModel.cpuCount());
                cores.set(info.core(), coreCpus);
            }
            coreCpus.set(cpu);
        }
        for (int core = 0; core < cores.size(); core++) {
            BitSet coreCpus = cores.get(core);
            if (coreCpus != null) {
                cores.set(core, new UnmodifiableBitSet(coreCpus));
            }
        }
        return Collections.unmodifiableList(cores);
    }

    private record PendingRequest(long sequence, UnmodifiableBitSet membership) {}

    public record EffectiveSocketTopology(
            int version, int socketId, BitSet effectiveCores, BitSet effectiveCpus, List<BitSet> effectiveCoreToCpu) {

        public EffectiveSocketTopology {
            effectiveCores = new UnmodifiableBitSet(Objects.requireNonNull(effectiveCores, "effectiveCores"));
            effectiveCpus = new UnmodifiableBitSet(Objects.requireNonNull(effectiveCpus, "effectiveCpus"));
            List<BitSet> ownedCoreToCpu =
                    new ArrayList<>(Objects.requireNonNull(effectiveCoreToCpu, "effectiveCoreToCpu")
                            .size());
            for (BitSet coreCpus : effectiveCoreToCpu) {
                ownedCoreToCpu.add(coreCpus == null ? null : new UnmodifiableBitSet(coreCpus));
            }
            effectiveCoreToCpu = Collections.unmodifiableList(ownedCoreToCpu);
        }
    }

    public record EffectiveSystemTopology(
            BitSet effectiveSockets,
            BitSet effectiveCores,
            BitSet effectiveCpus,
            List<EffectiveSocketTopology> socketTopologies,
            int globalVersion) {

        public EffectiveSystemTopology {
            effectiveSockets = new UnmodifiableBitSet(Objects.requireNonNull(effectiveSockets, "effectiveSockets"));
            effectiveCores = new UnmodifiableBitSet(Objects.requireNonNull(effectiveCores, "effectiveCores"));
            effectiveCpus = new UnmodifiableBitSet(Objects.requireNonNull(effectiveCpus, "effectiveCpus"));
            // Fixed socket spans deliberately retain null entries for inactive sockets. List.copyOf
            // rejects those holes, so copy into an unmodifiable list without changing the shape.
            socketTopologies = Collections.unmodifiableList(
                    new ArrayList<>(Objects.requireNonNull(socketTopologies, "socketTopologies")));
        }
    }
}
