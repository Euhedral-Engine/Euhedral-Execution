package euhedral.io.utils;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicReferenceArray;

public interface SystemUtilization {

    record NodeSnapshot(int nodeId, BitSet effectiveCores, long globalMemoryLimit,
                        long globalBytesUsed,
                        long nodeMemoryLimit,
                        double nodeMemoryUtilization,
                        CoreSnapshot[] coreSnapshots,
                        long lastUsageNs) {


    }

    record CoreSnapshot(
            int coreId,
            double quotaCpus,
            long period,
            long globalCpuCount,
            long globalMemoryLimit,
            long globalBytesUsed,
            long coreMemoryLimit,
            double coreMemoryUtilization,
            BitSet effectiveCpus,
            CpuSnapshot[] cpuSnapshots
    ) {

    }

    record CpuSnapshot(
            int cpuId,
            double quotaCpus,
            long period,
            int globalCpuCount,
            long globalMemoryLimit,
            long globalBytesUsed,
            long coreMemoryLimit,
            double cpuMemoryUtilization,
            double stallRatio,
            double throttleRatio,
            double pressure,
            long lastUsageNs
    ) {

    }

    record SystemSnapshot(long timeNs, int availableCpus, double quotaCpus, long period,
                          long cpuUsage,
                          long cpuThrottle,
                          BitSet effectiveCpus, double[] pressurePerCpu,
                          long memoryLimit, long memoryUsage, long inactiveFileMemory,
                          long ioBytes) {

    }


    record HardwareUtilization(long timestampNs, double quotaCpus, double quotaCpuUsage,
                               long period,
                               BitSet globalEffectiveCpus,
                               AtomicReferenceArray<Double> perQuotaCpuPressure,
                               double cpuThrottleRatio,
                               AtomicReferenceArray<Double> perQuotaCpuThrottleRatio,
                               long globalMemoryPool,
                               long perCpuMemoryPool,
                               double totalMemoryUtilization,
                               long memPerCpuUsageBytes,
                               double ioBytesPerSecond,
                               double ioPressure, SystemSnapshot snapshot) {

        // Get a snapshot of a node's utilization
        public NodeSnapshot getNodeSnapshot(int nodeId, BitSet[] effectiveCoreToCpu,
                double cpuQuotaPool) {
            if (effectiveCoreToCpu == null || effectiveCoreToCpu.length == 0 || nodeId < 0
                    || nodeId >= effectiveCoreToCpu.length || cpuQuotaPool < 0) {
                return null;
            }

            CoreSnapshot[] coreSnapshots = new CoreSnapshot[effectiveCoreToCpu.length];

            int cores = 0;
            BitSet effectiveCores = new BitSet(effectiveCoreToCpu.length);
            for (int i = 0; i < effectiveCoreToCpu.length; i++) {
                if (effectiveCoreToCpu[i] != null) {
                    int cardinality = effectiveCoreToCpu[i].cardinality();
                    cores += cardinality > 0 ? 1 : 0;
                    effectiveCores.set(i, cardinality > 0);
                }
            }

            double perCoreQuota = cpuQuotaPool / Math.max(cores, 1);

            long cpus = 0;
            for (int core = effectiveCores.nextSetBit(0); core >= 0;
                    core = effectiveCores.nextSetBit(core + 1)) {
                coreSnapshots[core] = getCoreSnapshot(core, effectiveCoreToCpu[core], perCoreQuota);
                cpus += coreSnapshots[core].effectiveCpus.cardinality();
            }
            long nodeMemoryUsageBytes = memPerCpuUsageBytes * cpus;

            return new NodeSnapshot(nodeId, effectiveCores,
                    globalMemoryPool, perCpuMemoryPool * cpus,
                    (long) (globalMemoryPool * totalMemoryUtilization),
                    (double) nodeMemoryUsageBytes / globalMemoryPool,
                    coreSnapshots, timestampNs);
        }

        // Get a snapshot of a core's utilization
        public CoreSnapshot getCoreSnapshot(int coreId, BitSet effectiveCpus, double cpuQuotaPool) {
            CpuSnapshot[] cpuSnapshots = new CpuSnapshot[effectiveCpus.length()];

            int cpuCount = 0;
            double perCpuQuota = cpuQuotaPool / Math.max(effectiveCpus.cardinality(), 1);
            for (int cpu = effectiveCpus.nextSetBit(0); cpu >= 0;
                    cpu = effectiveCpus.nextSetBit(cpu + 1)) {
                cpuSnapshots[cpu] = getCpuSnapshot(cpu, perCpuQuota, effectiveCpus.cardinality());
                cpuCount++;
            }

            long coreMemoryPool = perCpuMemoryPool * cpuCount;
            return new CoreSnapshot(coreId, cpuQuotaPool, period, effectiveCpus.cardinality(), globalMemoryPool,
                    (long) (globalMemoryPool * totalMemoryUtilization), coreMemoryPool,
                    (double) (memPerCpuUsageBytes * cpuCount) / coreMemoryPool, effectiveCpus,
                    cpuSnapshots);
        }


        public CpuSnapshot getCpuSnapshot(int cpuId, double cpuQuota, int coreCpuCount) {
            if (cpuId < 0 || cpuId >= perQuotaCpuPressure.length()) {
                return new CpuSnapshot(cpuId, cpuQuota, period, globalEffectiveCpus.cardinality(),
                        globalMemoryPool, (long) (globalMemoryPool * totalMemoryUtilization), 0, 0,
                        0, 0, 0, 0);
            }

            Double stallRatioObj = perQuotaCpuPressure.get(cpuId);
            Double throttleRatioObj = perQuotaCpuThrottleRatio.get(cpuId);

            double stallRatio = (stallRatioObj == null) ? 0.0 : stallRatioObj;
            double throttleRatio = (throttleRatioObj == null) ? 0.0 : throttleRatioObj;

            double cpuPressure = 1.0 - ((1.0 - stallRatio) * (1.0 - throttleRatio));

            double memUtil = (double) memPerCpuUsageBytes / (perCpuMemoryPool * coreCpuCount);
            double io = ioPressure * 0.8;
            double combinedPressure =
                    1.0 - ((1.0 - cpuPressure) *
                    (1.0 - io) *
                    (1.0 - memUtil));

            return new CpuSnapshot(cpuId, cpuQuota, period, globalEffectiveCpus.cardinality(),
                    globalMemoryPool, (long) (globalMemoryPool * totalMemoryUtilization),
                    coreCpuCount * perCpuMemoryPool,
                    (double) memPerCpuUsageBytes / (perCpuMemoryPool * coreCpuCount),
                    stallRatio,
                    throttleRatio,
                    combinedPressure,
                    timestampNs);
        }

        // Gets the total system pressure
        public double pressure() {
            double cpu = 1.0 - (1.0 - cpuThrottleRatio);
            double io = ioPressure * 0.8;

            return Math.clamp(Math.max(Math.max(cpu, totalMemoryUtilization), io), 0.0, 1.0);
        }
    }
}
