package io.euhedral_execution.benchmarks.cfd.execution;

import io.euhedral_execution.core.config.LatticeConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import java.time.Duration;
import java.util.BitSet;

/// Logical CPU budget shared by every parallel backend.
public record WorkerBudget(
        int[] requestedCpus, int[] effectiveCpus, Integer requestedWorkers, int driverCpu, String affinityCapability) {
    public WorkerBudget {
        requestedCpus = requestedCpus.clone();
        effectiveCpus = effectiveCpus.clone();
        if (effectiveCpus.length == 0
                || driverCpu < 0
                || affinityCapability == null
                || requestedWorkers != null && requestedWorkers <= 0) {
            throw new IllegalArgumentException("invalid worker budget");
        }
        requireIncreasing(requestedCpus);
        requireIncreasing(effectiveCpus);
        for (int cpu : effectiveCpus) {
            if (java.util.Arrays.binarySearch(requestedCpus, cpu) < 0) {
                throw new IllegalArgumentException("effective CPUs must belong to requested CPUs");
            }
        }
    }

    @Override
    public int[] requestedCpus() {
        return requestedCpus.clone();
    }

    @Override
    public int[] effectiveCpus() {
        return effectiveCpus.clone();
    }

    private static void requireIncreasing(int[] cpus) {
        int previous = -1;
        for (int cpu : cpus) {
            if (cpu <= previous) {
                throw new IllegalArgumentException("CPU IDs must be non-negative, unique and increasing");
            }
            previous = cpu;
        }
    }

    public static WorkerBudget resolve(BackendOptions options) {
        BitSet available = (BitSet) SystemInfo.getCpuSet().clone();
        available.and(ThreadTools.BASE_MASK);
        BitSet requested = new BitSet();
        if (options.cpus() == null) {
            requested.or(available);
        } else {
            for (String value : options.cpus().split(",")) {
                int cpu = Integer.parseInt(value);
                if (!available.get(cpu)) {
                    throw new IllegalArgumentException("requested CPU is unavailable: " + cpu);
                }
                requested.set(cpu);
            }
        }
        int driver = available.nextSetBit(0);
        if (driver < 0) {
            throw new IllegalArgumentException("no effective CPUs available");
        }
        for (int cpu = available.nextSetBit(0); cpu >= 0; cpu = available.nextSetBit(cpu + 1)) {
            if (SystemInfo.getCpuInfo(cpu).core() == 0) {
                driver = cpu;
                break;
            }
        }
        BitSet selected = new BitSet();
        int limit = options.backend().equals("serial")
                ? 1
                : options.workers() == null ? Integer.MAX_VALUE : options.workers();
        for (int cpu = requested.nextSetBit(0);
                cpu >= 0 && selected.cardinality() < limit;
                cpu = requested.nextSetBit(cpu + 1)) {
            selected.set(cpu);
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("no worker CPUs are available");
        }
        return new WorkerBudget(
                requested.stream().toArray(),
                selected.stream().toArray(),
                options.workers(),
                driver,
                ThreadTools.getAffinityCapability().name());
    }

    public int workerCount() {
        return effectiveCpus.length;
    }

    public int physicalCoreCount() {
        return (int) java.util.Arrays.stream(effectiveCpus)
                .map(cpu -> SystemInfo.getCpuInfo(cpu).core())
                .distinct()
                .count();
    }

    public ControlPlaneLattice lattice(long shutdownMillis) {
        var defaults = LatticeConfig.ofDefaults();
        BitSet selected = new BitSet();
        for (int cpu : effectiveCpus) {
            selected.set(cpu);
        }
        return ControlPlaneLattice.getOrCreate(
                new LatticeConfig(defaults.name(), selected, Duration.ofMillis(shutdownMillis), defaults.baseShard()));
    }
}
