package io.euhedral_execution.hardware_utils.common;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public final class SystemUtilization {

    private static UnmodifiableDoubleArray copyOf(UnmodifiableDoubleArray source) {
        double[] copy = new double[source.length()];
        source.copy(copy, 0, copy.length, 0);
        return new UnmodifiableDoubleArray(copy);
    }

    public enum MemorySnapshotIdx {
        MEMORY_LIMIT(0),
        MEMORY_USAGE(1),
        INACTIVE_FILE(2);

        public final int idx;

        MemorySnapshotIdx(int idx) {
            this.idx = idx;
        }
    }

    public record SocketSnapshot(
            int socketId,
            BitSet effectiveCores,
            long globalMemoryLimit,
            long globalBytesUsed,
            long memoryLimit,
            double memoryUtilization,
            CoreSnapshot[] coreSnapshots,
            long lastUsageNs) {

        public SocketSnapshot {
            effectiveCores = new UnmodifiableBitSet(Objects.requireNonNull(effectiveCores, "effectiveCores"));
            coreSnapshots =
                    Objects.requireNonNull(coreSnapshots, "coreSnapshots").clone();
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
            int result = Objects.hash(
                    socketId,
                    effectiveCores,
                    globalMemoryLimit,
                    globalBytesUsed,
                    memoryLimit,
                    memoryUtilization,
                    lastUsageNs);
            return 31 * result + Arrays.hashCode(coreSnapshots);
        }
    }

    public record CpuSnapshot(
            int cpuId,
            double quotaCpus,
            long period,
            int globalCpuCount,
            long globalMemoryLimit,
            long globalBytesUsed,
            long memoryLimit,
            double memoryUtilization,
            double stallRatio,
            double throttleRatio,
            double pressure,
            long lastUsageNs) {}

    public record CoreSnapshot(
            int coreId,
            double quotaCpus,
            long period,
            long globalCpuCount,
            long globalMemoryLimit,
            long globalBytesUsed,
            long memoryLimit,
            double memoryUtilization,
            BitSet effectiveCpus,
            CpuSnapshot[] cpuSnapshots) {

        public CoreSnapshot {
            effectiveCpus = new UnmodifiableBitSet(Objects.requireNonNull(effectiveCpus, "effectiveCpus"));
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
            int result = Objects.hash(
                    coreId,
                    quotaCpus,
                    period,
                    globalCpuCount,
                    globalMemoryLimit,
                    globalBytesUsed,
                    memoryLimit,
                    memoryUtilization,
                    effectiveCpus);
            return 31 * result + Arrays.hashCode(cpuSnapshots);
        }
    }

    public record SystemSnapshot(
            long timeNs,
            int totalCpus,
            double quotaCpus,
            long period,
            long cpuUsage,
            long cpuThrottle,
            UnmodifiableBitSet effectiveCpus,
            UnmodifiableDoubleArray pressurePerCpu,
            long memoryLimit,
            long memoryUsage,
            long inactiveFileMemory,
            long diskIOBytes) {

        public SystemSnapshot {
            if (totalCpus <= 0) {
                throw new IllegalArgumentException("totalCpus must be positive");
            }
            if (pressurePerCpu == null || pressurePerCpu.length() != totalCpus) {
                throw new IllegalArgumentException("pressurePerCpu length must match totalCpus");
            }
            if (effectiveCpus != null && effectiveCpus.length() > totalCpus) {
                throw new IllegalArgumentException("effectiveCpus out of bounds");
            }

            cpuUsage = Math.max(0, cpuUsage);
            cpuThrottle = Math.max(0, cpuThrottle);
            diskIOBytes = Math.max(0, diskIOBytes);
            memoryUsage = Math.max(0, memoryUsage);
            inactiveFileMemory = Math.max(0, inactiveFileMemory);

            if (memoryLimit < 0) {
                memoryLimit = Long.MAX_VALUE;
            }

            if (Double.isNaN(quotaCpus) || !Double.isFinite(quotaCpus) || quotaCpus < 0.0) {
                quotaCpus = 0.0;
            } else if (effectiveCpus != null && quotaCpus > effectiveCpus.cardinality()) {
                quotaCpus = effectiveCpus.cardinality();
            }

            period = Math.max(0, period);

            effectiveCpus = new UnmodifiableBitSet(Objects.requireNonNull(effectiveCpus, "effectiveCpus"));

            double[] pressureCopy = new double[pressurePerCpu.length()];
            for (int i = 0; i < pressureCopy.length; i++) {
                double p = pressurePerCpu.get(i);
                if (Double.isNaN(p) || !Double.isFinite(p) || p < 0.0) {
                    pressureCopy[i] = 0.0;
                } else if (p > 1.0) {
                    pressureCopy[i] = 1.0;
                } else if (p == -0.0) {
                    pressureCopy[i] = +0.0;
                } else {
                    pressureCopy[i] = p;
                }
            }
            pressurePerCpu = new UnmodifiableDoubleArray(pressureCopy);
        }

        public static SystemSnapshot create(
                long timeNs,
                int totalCpus,
                double quotaCpus,
                long period,
                long cpuUsage,
                long cpuThrottle,
                UnmodifiableBitSet effectiveCpus,
                double[] pressurePerCpu,
                long[] memorySnapshot,
                long ioBytes) {
            if (memorySnapshot.length != MemorySnapshotIdx.values().length) {
                throw new IllegalArgumentException(
                        "Memory snapshot should have " + MemorySnapshotIdx.values().length + " elements.");
            }

            long memoryLimit = memorySnapshot[MemorySnapshotIdx.MEMORY_LIMIT.idx];
            long memoryUsage = memorySnapshot[MemorySnapshotIdx.MEMORY_USAGE.idx];
            long inactiveFileMemory = memorySnapshot[MemorySnapshotIdx.INACTIVE_FILE.idx];
            return new SystemSnapshot(
                    timeNs,
                    totalCpus,
                    quotaCpus,
                    period,
                    cpuUsage,
                    cpuThrottle,
                    effectiveCpus,
                    UnmodifiableDoubleArray.wrap(pressurePerCpu),
                    memoryLimit,
                    memoryUsage,
                    inactiveFileMemory,
                    ioBytes);
        }
    }

    public record HardwareUtilization(
            long timestampNs,
            double quotaCpus,
            double quotaCpuUsage,
            long period,
            UnmodifiableBitSet globalEffectiveCpus,
            double cpuThrottleRatio,
            UnmodifiableDoubleArray perQuotaCpuThrottleRatio,
            UnmodifiableDoubleArray perQuotaCpuPressure,
            long globalMemoryPool,
            long perCpuMemoryPool,
            double totalMemoryUtilization,
            long memPerCpuUsageBytes,
            double diskIOBytesPerSecond,
            double diskIOPressure,
            SystemSnapshot snapshot) {

        public HardwareUtilization {
            globalEffectiveCpus =
                    new UnmodifiableBitSet(Objects.requireNonNull(globalEffectiveCpus, "globalEffectiveCpus"));

            if (Double.isNaN(quotaCpus) || !Double.isFinite(quotaCpus) || quotaCpus < 0.0) {
                quotaCpus = 0.0;
            } else if (quotaCpus > globalEffectiveCpus.cardinality()) {
                quotaCpus = globalEffectiveCpus.cardinality();
            }

            period = Math.max(0, period);

            if (snapshot != null) {
                if (snapshot.timeNs() != timestampNs) {
                    throw new IllegalArgumentException("timestampNs mismatch");
                }
                if (!snapshot.effectiveCpus().equals(globalEffectiveCpus)) {
                    throw new IllegalArgumentException("effectiveCpus mismatch");
                }
                if (Double.compare(snapshot.quotaCpus(), quotaCpus) != 0 || snapshot.period() != period) {
                    throw new IllegalArgumentException("Quota or period mismatch with nested snapshot");
                }
            }

            quotaCpuUsage = sanitizeRatio(quotaCpuUsage, true);
            cpuThrottleRatio = sanitizeRatio(cpuThrottleRatio, false);
            totalMemoryUtilization = sanitizeRatio(totalMemoryUtilization, false);
            diskIOPressure = sanitizeRatio(diskIOPressure, false);
            diskIOBytesPerSecond = sanitizeTelemetry(diskIOBytesPerSecond);

            globalMemoryPool = Math.max(0, globalMemoryPool);
            perCpuMemoryPool = Math.max(0, perCpuMemoryPool);
            memPerCpuUsageBytes = Math.max(0, memPerCpuUsageBytes);

            Objects.requireNonNull(perQuotaCpuThrottleRatio, "perQuotaCpuThrottleRatio");
            double[] throttleCopy = new double[perQuotaCpuThrottleRatio.length()];
            for (int i = 0; i < throttleCopy.length; i++) {
                throttleCopy[i] = sanitizeRatio(perQuotaCpuThrottleRatio.get(i), false);
            }
            perQuotaCpuThrottleRatio = new UnmodifiableDoubleArray(throttleCopy);

            Objects.requireNonNull(perQuotaCpuPressure, "perQuotaCpuPressure");
            double[] pressureCopy = new double[perQuotaCpuPressure.length()];
            for (int i = 0; i < pressureCopy.length; i++) {
                pressureCopy[i] = sanitizeRatio(perQuotaCpuPressure.get(i), false);
            }
            perQuotaCpuPressure = new UnmodifiableDoubleArray(pressureCopy);
        }

        private static long nonnegative(long value) {
            return Math.max(value, 0);
        }

        public static HardwareUtilization create(
                long timestampNs,
                double quotaCpus,
                double quotaCpuUsage,
                long period,
                UnmodifiableBitSet globalEffectiveCpus,
                double cpuThrottleRatio,
                double[] perQuotaCpuThrottleRatio,
                double[] perQuotaCpuPressure,
                long globalMemoryPool,
                long perCpuMemoryPool,
                double totalMemoryUtilization,
                long memPerCpuUsageBytes,
                double diskIOBytesPerSecond,
                double diskIOPressure,
                SystemSnapshot snapshot) {
            return new HardwareUtilization(
                    timestampNs,
                    quotaCpus,
                    quotaCpuUsage,
                    period,
                    globalEffectiveCpus,
                    cpuThrottleRatio,
                    UnmodifiableDoubleArray.wrap(perQuotaCpuThrottleRatio),
                    UnmodifiableDoubleArray.wrap(perQuotaCpuPressure),
                    globalMemoryPool,
                    perCpuMemoryPool,
                    totalMemoryUtilization,
                    memPerCpuUsageBytes,
                    diskIOBytesPerSecond,
                    diskIOPressure,
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

        private static double sanitizeRatio(double val, boolean isTelemetry) {
            if (Double.isNaN(val) || val < 0.0) return 0.0;
            if (isTelemetry && val == Double.POSITIVE_INFINITY) return Double.MAX_VALUE;
            if (!isTelemetry && (!Double.isFinite(val))) return 0.0;
            if (val > 1.0) return 1.0;
            if (val == -0.0) return 0.0;
            return val;
        }

        private static double sanitizeTelemetry(double val) {
            if (Double.isNaN(val) || val < 0.0) return 0.0;
            if (val == Double.POSITIVE_INFINITY) return Double.MAX_VALUE;
            if (val == -0.0) return 0.0;
            return val;
        }

        /// Get a snapshot of a socket's utilization
        public SocketSnapshot getSocketSnapshot(int socketId, List<BitSet> effectiveCoreToCpu, double cpuQuotaPool) {
            if (effectiveCoreToCpu == null
                    || effectiveCoreToCpu.isEmpty()
                    || !Double.isFinite(cpuQuotaPool)
                    || cpuQuotaPool < 0) {
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

            for (int core = effectiveCores.nextSetBit(0); core >= 0; core = effectiveCores.nextSetBit(core + 1)) {
                coreSnapshots[core] = getCoreSnapshot(core, effectiveCoreToCpu.get(core), perCoreQuota);
            }
            int cpus = socketCpus.cardinality();
            long memoryLimit = saturatedMultiply(perCpuMemoryPool, cpus);
            long memoryUsage = saturatedMultiply(memPerCpuUsageBytes, cpus);

            return new SocketSnapshot(
                    socketId,
                    effectiveCores,
                    nonnegative(globalMemoryPool),
                    saturatedProduct(globalMemoryPool, totalMemoryUtilization),
                    memoryLimit,
                    finiteUtilization(memoryUsage, memoryLimit),
                    coreSnapshots,
                    timestampNs);
        }

        /// Gets a snapshot of a core's utilization
        public @NonNull CoreSnapshot getCoreSnapshot(int coreId, BitSet effectiveCpus, double cpuQuotaPool) {
            Objects.requireNonNull(effectiveCpus, "effectiveCpus");
            CpuSnapshot[] cpuSnapshots = new CpuSnapshot[effectiveCpus.length()];

            double perCpuQuota = cpuQuotaPool / Math.max(effectiveCpus.cardinality(), 1);
            for (int cpu = effectiveCpus.nextSetBit(0); cpu >= 0; cpu = effectiveCpus.nextSetBit(cpu + 1)) {
                cpuSnapshots[cpu] = getCpuSnapshot(cpu, perCpuQuota, effectiveCpus.cardinality());
            }

            int cpuCount = effectiveCpus.cardinality();
            long coreMemoryLimit = saturatedMultiply(perCpuMemoryPool, cpuCount);
            long coreMemoryUsage = saturatedMultiply(memPerCpuUsageBytes, cpuCount);
            return new CoreSnapshot(
                    coreId,
                    cpuQuotaPool,
                    period,
                    globalEffectiveCpus.cardinality(),
                    nonnegative(globalMemoryPool),
                    saturatedProduct(globalMemoryPool, totalMemoryUtilization),
                    coreMemoryLimit,
                    finiteUtilization(coreMemoryUsage, coreMemoryLimit),
                    effectiveCpus,
                    cpuSnapshots);
        }

        /// Gets a snapshot of a cpu's utilization
        public @NonNull CpuSnapshot getCpuSnapshot(int cpuId, double cpuQuota, int coreCpuCount) {
            if (cpuId < 0 || !globalEffectiveCpus.get(cpuId)) {
                return new CpuSnapshot(
                        cpuId,
                        cpuQuota,
                        period,
                        globalEffectiveCpus.cardinality(),
                        nonnegative(globalMemoryPool),
                        saturatedProduct(globalMemoryPool, totalMemoryUtilization),
                        0,
                        0,
                        0,
                        0,
                        0,
                        timestampNs);
            }
            if (cpuId >= perQuotaCpuPressure.length() || cpuId >= perQuotaCpuThrottleRatio.length()) {
                throw new IllegalStateException("Active CPU " + cpuId
                        + " is not covered by pressure/throttle spans "
                        + perQuotaCpuPressure.length() + "/"
                        + perQuotaCpuThrottleRatio.length());
            }

            double stallRatio = perQuotaCpuPressure.get(cpuId);
            double throttleRatio = perQuotaCpuThrottleRatio.get(cpuId);

            double pressure = perQuotaCpuPressure.get(cpuId);

            long memoryLimit = nonnegative(perCpuMemoryPool);
            long memoryUsage = nonnegative(memPerCpuUsageBytes);
            double memUtil = finiteUtilization(memoryUsage, memoryLimit);

            return new CpuSnapshot(
                    cpuId,
                    cpuQuota,
                    period,
                    globalEffectiveCpus.cardinality(),
                    nonnegative(globalMemoryPool),
                    saturatedProduct(globalMemoryPool, totalMemoryUtilization),
                    memoryLimit,
                    memUtil,
                    stallRatio,
                    throttleRatio,
                    pressure,
                    timestampNs);
        }

        /// Gets the total system pressure
        public double pressure() {
            if (globalEffectiveCpus.isEmpty()) {
                return 1.0;
            }
            double max = 0.0;
            for (int i = globalEffectiveCpus.nextSetBit(0); i >= 0; i = globalEffectiveCpus.nextSetBit(i + 1)) {
                if (i < perQuotaCpuPressure.length()) {
                    double p = perQuotaCpuPressure.get(i);
                    if (p > max) {
                        max = p;
                    }
                }
            }
            return Math.min(1.0, Math.max(0.0, max));
        }
    }
}
