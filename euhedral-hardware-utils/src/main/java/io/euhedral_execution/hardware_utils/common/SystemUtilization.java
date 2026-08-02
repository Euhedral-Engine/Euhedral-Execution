package io.euhedral_execution.hardware_utils.common;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public final class SystemUtilization {

    public enum MemorySnapshotIdx {
        MEMORY_LIMIT(0),
        MEMORY_USAGE(1),
        INACTIVE_FILE(2);

        public final int idx;

        MemorySnapshotIdx(int idx) {
            this.idx = idx;
        }
    }

    private static UnmodifiableDoubleArray copyOf(UnmodifiableDoubleArray source) {
        double[] copy = new double[source.length()];
        source.copy(copy, 0, copy.length, 0);
        return new UnmodifiableDoubleArray(copy);
    }

    public record SocketSnapshot(int socketId, BitSet effectiveCores, long globalMemoryLimit,
                                 long globalBytesUsed, long memoryLimit,
                                 double memoryUtilization,
                                 CoreSnapshot[] coreSnapshots, long lastUsageNs) {

        public SocketSnapshot {
            effectiveCores = new UnmodifiableBitSet(
                    Objects.requireNonNull(effectiveCores, "effectiveCores"));
            coreSnapshots = Objects.requireNonNull(coreSnapshots, "coreSnapshots").clone();
        }

        @Override
        public CoreSnapshot[] coreSnapshots() {
            return coreSnapshots.clone();
        }

        @Override
        public final boolean equals(Object other) {
            if (!(other instanceof SocketSnapshot snapshot)) {
                return false;
            }
            return socketId == snapshot.socketId
                    && globalMemoryLimit == snapshot.globalMemoryLimit
                    && globalBytesUsed == snapshot.globalBytesUsed
                    && memoryLimit == snapshot.memoryLimit
                    && Double.compare(memoryUtilization, snapshot.memoryUtilization) == 0
                    && lastUsageNs == snapshot.lastUsageNs
                    && effectiveCores.equals(snapshot.effectiveCores)
                    && Arrays.equals(coreSnapshots, snapshot.coreSnapshots);
        }

        @Override
        public final int hashCode() {
            int result = Objects.hash(socketId, effectiveCores, globalMemoryLimit, globalBytesUsed,
                    memoryLimit, memoryUtilization, lastUsageNs);
            return 31 * result + Arrays.hashCode(coreSnapshots);
        }
    }

    public record CpuSnapshot(int cpuId, double quotaCpus, long period, int globalCpuCount,
                              long globalMemoryLimit, long globalBytesUsed, long memoryLimit,
                              double memoryUtilization, double stallRatio, double throttleRatio,
                              double pressure, long lastUsageNs) {

    }

    public record CoreSnapshot(int coreId, double quotaCpus, long period, long globalCpuCount,
                               long globalMemoryLimit, long globalBytesUsed, long memoryLimit,
                               double memoryUtilization, BitSet effectiveCpus,
                               CpuSnapshot[] cpuSnapshots) {

        public CoreSnapshot {
            effectiveCpus = new UnmodifiableBitSet(
                    Objects.requireNonNull(effectiveCpus, "effectiveCpus"));
            cpuSnapshots = Objects.requireNonNull(cpuSnapshots, "cpuSnapshots").clone();
        }

        @Override
        public CpuSnapshot[] cpuSnapshots() {
            return cpuSnapshots.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof CoreSnapshot snapshot) {
                return coreId == snapshot.coreId
                        && Double.compare(quotaCpus, snapshot.quotaCpus) == 0
                        && period == snapshot.period
                        && globalCpuCount == snapshot.globalCpuCount
                        && globalMemoryLimit == snapshot.globalMemoryLimit
                        && globalBytesUsed == snapshot.globalBytesUsed
                        && memoryLimit == snapshot.memoryLimit
                        && Double.compare(memoryUtilization, snapshot.memoryUtilization) == 0
                        && effectiveCpus.equals(snapshot.effectiveCpus)
                        && Arrays.equals(cpuSnapshots, snapshot.cpuSnapshots);
            }
            return false;
        }

        @Override
        public final int hashCode() {
            int result = Objects.hash(coreId, quotaCpus, period, globalCpuCount,
                    globalMemoryLimit, globalBytesUsed, memoryLimit, memoryUtilization,
                    effectiveCpus);
            return 31 * result + Arrays.hashCode(cpuSnapshots);
        }
    }

    public record SystemSnapshot(long timeNs, int totalCpus, double quotaCpus, long period,
                                 long cpuUsage,
                                 long cpuThrottle, UnmodifiableBitSet effectiveCpus,
                                 UnmodifiableDoubleArray pressurePerCpu, long memoryLimit,
                                 long memoryUsage,
                                 long inactiveFileMemory, long diskIOBytes) {

        public SystemSnapshot {
            effectiveCpus = new UnmodifiableBitSet(
                    Objects.requireNonNull(effectiveCpus, "effectiveCpus"));
            pressurePerCpu = copyOf(
                    Objects.requireNonNull(pressurePerCpu, "pressurePerCpu"));
        }

        public static SystemSnapshot create(long timeNs, int totalCpus, double quotaCpus,
                long period, long cpuUsage, long cpuThrottle, UnmodifiableBitSet effectiveCpus,
                double[] pressurePerCpu,
                long[] memorySnapshot, long ioBytes) {
            if (memorySnapshot.length != MemorySnapshotIdx.values().length) {
                throw new IllegalArgumentException(
                        "Memory snapshot should have " + MemorySnapshotIdx.values().length
                                + " elements.");
            }

            long memoryLimit = memorySnapshot[MemorySnapshotIdx.MEMORY_LIMIT.idx];
            long memoryUsage = memorySnapshot[MemorySnapshotIdx.MEMORY_USAGE.idx];
            long inactiveFileMemory = memorySnapshot[MemorySnapshotIdx.INACTIVE_FILE.idx];
            return new SystemSnapshot(timeNs, totalCpus, quotaCpus, period, cpuUsage, cpuThrottle,
                    effectiveCpus, UnmodifiableDoubleArray.wrap(pressurePerCpu),
                    memoryLimit, memoryUsage,
                    inactiveFileMemory, ioBytes);
        }
    }

    public record HardwareUtilization(long timestampNs, double quotaCpus, double quotaCpuUsage,
                                      long period, UnmodifiableBitSet globalEffectiveCpus,
                                      double cpuThrottleRatio,
                                      UnmodifiableDoubleArray perQuotaCpuThrottleRatio,
                                      UnmodifiableDoubleArray perQuotaCpuPressure,
                                      long globalMemoryPool, long perCpuMemoryPool,
                                      double totalMemoryUtilization, long memPerCpuUsageBytes,
                                      double diskIOBytesPerSecond, double diskIOPressure,
                                      SystemSnapshot snapshot) {

        private static long nonnegative(long value) {
            return Math.max(value, 0);
        }

        public static HardwareUtilization create(long timestampNs, double quotaCpus,
                double quotaCpuUsage,
                long period, UnmodifiableBitSet globalEffectiveCpus,
                double cpuThrottleRatio,
                double[] perQuotaCpuThrottleRatio,
                double[] perQuotaCpuPressure,
                long globalMemoryPool, long perCpuMemoryPool,
                double totalMemoryUtilization, long memPerCpuUsageBytes,
                double diskIOBytesPerSecond, double diskIOPressure,
                SystemSnapshot snapshot) {
            return new HardwareUtilization(
                    timestampNs,
                    quotaCpus, quotaCpuUsage, period, globalEffectiveCpus,
                    cpuThrottleRatio,
                    UnmodifiableDoubleArray.wrap(perQuotaCpuThrottleRatio),
                    UnmodifiableDoubleArray.wrap(perQuotaCpuPressure),
                    globalMemoryPool,
                    perCpuMemoryPool,
                    totalMemoryUtilization,
                    memPerCpuUsageBytes,
                    diskIOBytesPerSecond, diskIOPressure,
                    snapshot);
        }

        private static long saturatedMultiply(long value, int multiplier) {
            long left = nonnegative(value);
            long right = Math.max(multiplier, 0);
            if (left == 0 || right == 0) {
                return 0;
            }
            return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
        }

        private static long saturatedProduct(long pool, double ratio) {
            double product = (double) nonnegative(pool) * ratio;
            if (Double.isNaN(product) || ratio < 0 || product <= 0) {
                return 0;
            }
            if (Double.isInfinite(product) || product >= Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            return (long) product;
        }

        private static double finiteUtilization(long usage, long limit) {
            return (double) nonnegative(usage) / Math.max(nonnegative(limit), 1);
        }

        public HardwareUtilization {
            globalEffectiveCpus = new UnmodifiableBitSet(
                    Objects.requireNonNull(globalEffectiveCpus, "globalEffectiveCpus"));
            perQuotaCpuThrottleRatio = copyOf(Objects.requireNonNull(
                    perQuotaCpuThrottleRatio, "perQuotaCpuThrottleRatio"));
            perQuotaCpuPressure = copyOf(
                    Objects.requireNonNull(perQuotaCpuPressure, "perQuotaCpuPressure"));
        }

        /// Get a snapshot of a socket's utilization
        public SocketSnapshot getSocketSnapshot(int socketId, List<BitSet> effectiveCoreToCpu,
                double cpuQuotaPool) {
            if (effectiveCoreToCpu == null || effectiveCoreToCpu.isEmpty()
                    || !Double.isFinite(cpuQuotaPool) || cpuQuotaPool < 0) {
                return null;
            }

            CoreSnapshot[] coreSnapshots = new CoreSnapshot[effectiveCoreToCpu.size()];

            int cores = 0;
            BitSet effectiveCores = new BitSet(effectiveCoreToCpu.size());
            BitSet socketCpus = new BitSet();
            for (int i = 0; i < effectiveCoreToCpu.size(); i++) {
                BitSet coreCpus = effectiveCoreToCpu.get(i);
                if (coreCpus != null && !coreCpus.isEmpty()) {
                    BitSet duplicate = (BitSet) socketCpus.clone();
                    duplicate.and(coreCpus);
                    if (!duplicate.isEmpty()) {
                        throw new IllegalArgumentException("CPU belongs to more than one core");
                    }
                    BitSet outside = (BitSet) coreCpus.clone();
                    outside.andNot(globalEffectiveCpus);
                    if (!outside.isEmpty()) {
                        throw new IllegalArgumentException("Core contains a non-effective CPU");
                    }
                    socketCpus.or(coreCpus);
                    cores++;
                    effectiveCores.set(i);
                }
            }

            if (cores == 0) {
                return null;
            }

            double perCoreQuota = cpuQuotaPool / Math.max(cores, 1);

            for (int core = effectiveCores.nextSetBit(0); core >= 0;
                    core = effectiveCores.nextSetBit(core + 1)) {
                coreSnapshots[core] = getCoreSnapshot(core, effectiveCoreToCpu.get(core),
                        perCoreQuota);
            }
            int cpus = socketCpus.cardinality();
            long memoryLimit = saturatedMultiply(perCpuMemoryPool, cpus);
            long memoryUsage = saturatedMultiply(memPerCpuUsageBytes, cpus);

            return new SocketSnapshot(socketId, effectiveCores, nonnegative(globalMemoryPool),
                    saturatedProduct(globalMemoryPool, totalMemoryUtilization), memoryLimit,
                    finiteUtilization(memoryUsage, memoryLimit), coreSnapshots, timestampNs);
        }

        /// Gets a snapshot of a core's utilization
        public @NonNull CoreSnapshot getCoreSnapshot(int coreId, BitSet effectiveCpus,
                double cpuQuotaPool) {
            Objects.requireNonNull(effectiveCpus, "effectiveCpus");
            CpuSnapshot[] cpuSnapshots = new CpuSnapshot[effectiveCpus.length()];

            double perCpuQuota = cpuQuotaPool / Math.max(effectiveCpus.cardinality(), 1);
            for (int cpu = effectiveCpus.nextSetBit(0); cpu >= 0;
                    cpu = effectiveCpus.nextSetBit(cpu + 1)) {
                cpuSnapshots[cpu] = getCpuSnapshot(cpu, perCpuQuota, effectiveCpus.cardinality());
            }

            int cpuCount = effectiveCpus.cardinality();
            long coreMemoryLimit = saturatedMultiply(perCpuMemoryPool, cpuCount);
            long coreMemoryUsage = saturatedMultiply(memPerCpuUsageBytes, cpuCount);
            return new CoreSnapshot(coreId, cpuQuotaPool, period,
                    globalEffectiveCpus.cardinality(), nonnegative(globalMemoryPool),
                    saturatedProduct(globalMemoryPool, totalMemoryUtilization), coreMemoryLimit,
                    finiteUtilization(coreMemoryUsage, coreMemoryLimit),
                    effectiveCpus, cpuSnapshots);
        }

        /// Gets a snapshot of a cpu's utilization
        public @NonNull CpuSnapshot getCpuSnapshot(int cpuId, double cpuQuota, int coreCpuCount) {
            if (cpuId < 0 || !globalEffectiveCpus.get(cpuId)) {
                return new CpuSnapshot(cpuId, cpuQuota, period, globalEffectiveCpus.cardinality(),
                        nonnegative(globalMemoryPool),
                        saturatedProduct(globalMemoryPool, totalMemoryUtilization), 0, 0,
                        0, 0, 0, timestampNs);
            }
            if (cpuId >= perQuotaCpuPressure.length()
                    || cpuId >= perQuotaCpuThrottleRatio.length()) {
                throw new IllegalStateException("Active CPU " + cpuId
                        + " is not covered by pressure/throttle spans "
                        + perQuotaCpuPressure.length() + "/"
                        + perQuotaCpuThrottleRatio.length());
            }

            double stallRatio = perQuotaCpuPressure.get(cpuId);
            double throttleRatio = perQuotaCpuThrottleRatio.get(cpuId);

            double cpuPressure = 1.0 - ((1.0 - stallRatio) * (1.0 - throttleRatio));

            long memoryLimit = nonnegative(perCpuMemoryPool);
            long memoryUsage = nonnegative(memPerCpuUsageBytes);
            double memUtil = finiteUtilization(memoryUsage, memoryLimit);
            double io = diskIOPressure * 0.8;
            double combinedPressure = 1.0 - ((1.0 - cpuPressure) * (1.0 - io) * (1.0 - memUtil));

            return new CpuSnapshot(cpuId, cpuQuota, period, globalEffectiveCpus.cardinality(),
                    nonnegative(globalMemoryPool),
                    saturatedProduct(globalMemoryPool, totalMemoryUtilization), memoryLimit,
                    memUtil, stallRatio,
                    throttleRatio, combinedPressure, timestampNs);
        }

        /// Gets the total system pressure
        public double pressure() {
            double cpu = 1.0 - (1.0 - cpuThrottleRatio);
            double io = diskIOPressure * 0.8;
            double pressure = Math.max(Math.max(cpu, totalMemoryUtilization), io);

            return Math.min(1.0, Math.max(0.0, pressure));
        }
    }
}
