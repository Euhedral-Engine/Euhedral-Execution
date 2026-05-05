package euhedral.hardware_utils.common;

import java.util.BitSet;
import java.util.List;
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

    public record SocketSnapshot(int socketId, BitSet effectiveCores, long globalMemoryLimit,
                                 long globalBytesUsed, long memoryLimit,
                                 double memoryUtilization,
                                 CoreSnapshot[] coreSnapshots, long lastUsageNs) {


    }

    public record CoreSnapshot(int coreId, double quotaCpus, long period, long globalCpuCount,
                               long globalMemoryLimit, long globalBytesUsed, long memoryLimit,
                               double memoryUtilization, BitSet effectiveCpus,
                               CpuSnapshot[] cpuSnapshots) {

    }

    public record CpuSnapshot(int cpuId, double quotaCpus, long period, int globalCpuCount,
                              long globalMemoryLimit, long globalBytesUsed, long memoryLimit,
                              double memoryUtilization, double stallRatio, double throttleRatio,
                              double pressure, long lastUsageNs) {

    }

    public record SystemSnapshot(long timeNs, int totalCpus, double quotaCpus, long period,
                                 long cpuUsage,
                                 long cpuThrottle, UnmodifiableBitSet effectiveCpus,
                                 UnmodifiableDoubleArray pressurePerCpu, long memoryLimit,
                                 long memoryUsage,
                                 long inactiveFileMemory, long ioBytes) {

        public static SystemSnapshot create(long timeNs, int totalCpus, double quotaCpus,
                long period, long cpuUsage, long cpuThrottle, UnmodifiableBitSet effectiveCpus,
                double[] pressurePerCpu,
                long[] memorySnapshot, long ioBytes) {
            assert (memorySnapshot.length >= 3);

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
                                      long period, BitSet globalEffectiveCpus,
                                      double cpuThrottleRatio,
                                      UnmodifiableDoubleArray perQuotaCpuThrottleRatio,
                                      UnmodifiableDoubleArray perQuotaCpuPressure,
                                      long globalMemoryPool, long perCpuMemoryPool,
                                      double totalMemoryUtilization, long memPerCpuUsageBytes,
                                      double ioBytesPerSecond, double ioPressure,
                                      SystemSnapshot snapshot) {

        public static HardwareUtilization create(long timestampNs, double quotaCpus,
                double quotaCpuUsage,
                long period, BitSet globalEffectiveCpus,
                double cpuThrottleRatio,
                double[] perQuotaCpuThrottleRatio,
                double[] perQuotaCpuPressure,
                long globalMemoryPool, long perCpuMemoryPool,
                double totalMemoryUtilization, long memPerCpuUsageBytes,
                double ioBytesPerSecond, double ioPressure,
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
                    ioBytesPerSecond, ioPressure,
                    snapshot);
        }

        /// Get a snapshot of a socket's utilization
        public SocketSnapshot getSocketSnapshot(int socketId, List<BitSet> effectiveCoreToCpu,
                double cpuQuotaPool) {
            if (effectiveCoreToCpu == null || effectiveCoreToCpu.isEmpty() || socketId < 0
                    || socketId >= effectiveCoreToCpu.size() || cpuQuotaPool < 0) {
                return null;
            }

            CoreSnapshot[] coreSnapshots = new CoreSnapshot[effectiveCoreToCpu.size()];

            int cores = 0;
            BitSet effectiveCores = new BitSet(effectiveCoreToCpu.size());
            for (int i = 0; i < effectiveCoreToCpu.size(); i++) {
                if (effectiveCoreToCpu.get(i) != null) {
                    int cardinality = effectiveCoreToCpu.get(i).cardinality();
                    cores += cardinality > 0 ? 1 : 0;
                    effectiveCores.set(i, cardinality > 0);
                }
            }

            double perCoreQuota = cpuQuotaPool / Math.max(cores, 1);

            long cpus = 0;
            for (int core = effectiveCores.nextSetBit(0); core >= 0;
                    core = effectiveCores.nextSetBit(core + 1)) {
                coreSnapshots[core] = getCoreSnapshot(core, effectiveCoreToCpu.get(core),
                        perCoreQuota);
                cpus += coreSnapshots[core].effectiveCpus.cardinality();
            }
            long socketMemoryUsageBytes = memPerCpuUsageBytes * cpus;

            return new SocketSnapshot(socketId, effectiveCores, globalMemoryPool,
                    perCpuMemoryPool * cpus, (long) (globalMemoryPool * totalMemoryUtilization),
                    (double) socketMemoryUsageBytes / globalMemoryPool, coreSnapshots, timestampNs);
        }

        /// Gets a snapshot of a core's utilization
        public @NonNull CoreSnapshot getCoreSnapshot(int coreId, BitSet effectiveCpus,
                double cpuQuotaPool) {
            CpuSnapshot[] cpuSnapshots = new CpuSnapshot[effectiveCpus.length()];

            int cpuCount = 0;
            double perCpuQuota = cpuQuotaPool / Math.max(effectiveCpus.cardinality(), 1);
            for (int cpu = effectiveCpus.nextSetBit(0); cpu >= 0;
                    cpu = effectiveCpus.nextSetBit(cpu + 1)) {
                cpuSnapshots[cpu] = getCpuSnapshot(cpu, perCpuQuota, effectiveCpus.cardinality());
                cpuCount++;
            }

            long coreMemoryPool = perCpuMemoryPool * cpuCount;
            return new CoreSnapshot(coreId, cpuQuotaPool, period, effectiveCpus.cardinality(),
                    globalMemoryPool, (long) (globalMemoryPool * totalMemoryUtilization),
                    coreMemoryPool, (double) (memPerCpuUsageBytes * cpuCount) / coreMemoryPool,
                    effectiveCpus, cpuSnapshots);
        }

        /// Gets a snapshot of a cpu's utilization
        public @NonNull CpuSnapshot getCpuSnapshot(int cpuId, double cpuQuota, int coreCpuCount) {
            if (cpuId < 0 || cpuId >= perQuotaCpuPressure.length()) {
                return new CpuSnapshot(cpuId, cpuQuota, period, globalEffectiveCpus.cardinality(),
                        globalMemoryPool, (long) (globalMemoryPool * totalMemoryUtilization), 0, 0,
                        0, 0, 0, 0);
            }

            double stallRatio = perQuotaCpuPressure.get(cpuId);
            double throttleRatio = perQuotaCpuThrottleRatio.get(cpuId);

            double cpuPressure = 1.0 - ((1.0 - stallRatio) * (1.0 - throttleRatio));

            double memUtil = (double) memPerCpuUsageBytes / (perCpuMemoryPool * coreCpuCount);
            double io = ioPressure * 0.8;
            double combinedPressure = 1.0 - ((1.0 - cpuPressure) * (1.0 - io) * (1.0 - memUtil));

            return new CpuSnapshot(cpuId, cpuQuota, period, globalEffectiveCpus.cardinality(),
                    globalMemoryPool, (long) (globalMemoryPool * totalMemoryUtilization),
                    coreCpuCount * perCpuMemoryPool,
                    (double) memPerCpuUsageBytes / (perCpuMemoryPool * coreCpuCount), stallRatio,
                    throttleRatio, combinedPressure, timestampNs);
        }

        /// Gets the total system pressure
        public double pressure() {
            double cpu = 1.0 - (1.0 - cpuThrottleRatio);
            double io = ioPressure * 0.8;
            double pressure = Math.max(Math.max(cpu, totalMemoryUtilization), io);

            return Math.min(1.0, Math.max(0.0, pressure));
        }
    }
}
