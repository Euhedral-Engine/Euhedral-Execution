package euhedral.io.resource_monitoring;

import static euhedral.io.utils.MathFunctions.clampDouble;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.jspecify.annotations.Nullable;

public interface SystemUtilization {

    record SocketSnapshot(int socketId, BitSet effectiveCores, BitSet pCores, long globalMemoryLimit,
                          long globalBytesUsed, long nodeMemoryLimit, double nodeMemoryUtilization,
                          CoreSnapshot[] coreSnapshots, long lastUsageNs) {


    }

    record CoreSnapshot(int coreId, double quotaCpus, long period, long globalCpuCount,
                        long globalMemoryLimit, long globalBytesUsed, long coreMemoryLimit,
                        double coreMemoryUtilization, BitSet effectiveCpus,
                        CpuSnapshot[] cpuSnapshots, boolean isPCore) {

    }

    record CpuSnapshot(int cpuId, double quotaCpus, long period, int globalCpuCount,
                       long globalMemoryLimit, long globalBytesUsed, long coreMemoryLimit,
                       double cpuMemoryUtilization, double stallRatio, double throttleRatio,
                       double pressure, long lastUsageNs, boolean isPCore) {

    }

    /// pCpus vs. eCpus = Intel P-Core or E-Core | AMD Classic(P) Dense(E)
    record SystemSnapshot(long timeNs, int totalCpus, double quotaCpus, long period, long cpuUsage,
                          long cpuThrottle, BitSet pCpus, BitSet eCpus, BitSet effectiveCpus,
                          double[] pressurePerCpu, long memoryLimit, long memoryUsage,
                          long inactiveFileMemory, long ioBytes) {

        public static SystemSnapshot create(long timeNs, int totalCpus, double quotaCpus,
                long period, long cpuUsage, long cpuThrottle, long[][] pCpuMasks,
                long[][] eCpuMasks, BitSet effectiveCpus, double[] pressurePerCpu,
                long[] memorySnapshot, long ioBytes) {
            BitSet pCpus = toBitSet(pCpuMasks);
            BitSet eCpus = toBitSet(eCpuMasks);
            if (pCpus == null || eCpus == null) {
                pCpus = new BitSet(totalCpus);
                eCpus = new BitSet(totalCpus);
                pCpus.set(0, totalCpus);
            }
            long memoryLimit = memorySnapshot.length > 0 ? memorySnapshot[0] : 0;
            long memoryUsage = memorySnapshot.length > 1 ? memorySnapshot[1] : 0;
            long inactiveFileMemory = memorySnapshot.length > 2 ? memorySnapshot[2] : 0;
            return new SystemSnapshot(timeNs, totalCpus, quotaCpus, period, cpuUsage, cpuThrottle,
                    pCpus, eCpus, effectiveCpus, pressurePerCpu, memoryLimit, memoryUsage,
                    inactiveFileMemory, ioBytes);
        }

        public static @Nullable BitSet toBitSet(long[][] groupedMasks) {
            if (groupedMasks == null) {
                return null;
            }

            BitSet bs = new BitSet();
            int idx = 0;
            for (long[] val : groupedMasks) {
                long mask = val[0];
                int shifts = 0;
                while (mask != 0) {
                    if ((mask & 1L) != 0) {
                        bs.set(idx);
                    }
                    mask >>>= 1;
                    idx++;
                    shifts++;
                }
                idx += 64 - shifts;
            }
            return bs;
        }
    }


    record HardwareUtilization(long timestampNs, double quotaCpus, double quotaCpuUsage,
                               long period, BitSet globalEffectiveCpus,
                               AtomicReferenceArray<Double> perQuotaCpuPressure,
                               double cpuThrottleRatio,
                               AtomicReferenceArray<Double> perQuotaCpuThrottleRatio,
                               long globalMemoryPool, long perCpuMemoryPool,
                               double totalMemoryUtilization, long memPerCpuUsageBytes,
                               double ioBytesPerSecond, double ioPressure,
                               SystemSnapshot snapshot) {

        // Get a snapshot of a node's utilization
        public SocketSnapshot getNodeSnapshot(int nodeId, BitSet[] effectiveCoreToCpu,
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

            BitSet pCores = new BitSet(effectiveCores.length());
            double perCoreQuota = cpuQuotaPool / Math.max(cores, 1);

            long cpus = 0;
            for (int core = effectiveCores.nextSetBit(0); core >= 0;
                    core = effectiveCores.nextSetBit(core + 1)) {
                coreSnapshots[core] = getCoreSnapshot(core, effectiveCoreToCpu[core], perCoreQuota);
                pCores.set(core, coreSnapshots[core].isPCore);
                cpus += coreSnapshots[core].effectiveCpus.cardinality();
            }
            long nodeMemoryUsageBytes = memPerCpuUsageBytes * cpus;

            return new SocketSnapshot(nodeId, effectiveCores, pCores, globalMemoryPool,
                    perCpuMemoryPool * cpus, (long) (globalMemoryPool * totalMemoryUtilization),
                    (double) nodeMemoryUsageBytes / globalMemoryPool, coreSnapshots, timestampNs);
        }

        // Get a snapshot of a core's utilization
        public CoreSnapshot getCoreSnapshot(int coreId, BitSet effectiveCpus, double cpuQuotaPool) {
            CpuSnapshot[] cpuSnapshots = new CpuSnapshot[effectiveCpus.length()];

            int sampleCpu = 0;
            int cpuCount = 0;
            double perCpuQuota = cpuQuotaPool / Math.max(effectiveCpus.cardinality(), 1);
            for (int cpu = effectiveCpus.nextSetBit(0); cpu >= 0;
                    cpu = effectiveCpus.nextSetBit(cpu + 1)) {
                cpuSnapshots[cpu] = getCpuSnapshot(cpu, perCpuQuota, effectiveCpus.cardinality());
                cpuCount++;
                sampleCpu = cpu;
            }

            long coreMemoryPool = perCpuMemoryPool * cpuCount;
            return new CoreSnapshot(coreId, cpuQuotaPool, period, effectiveCpus.cardinality(),
                    globalMemoryPool, (long) (globalMemoryPool * totalMemoryUtilization),
                    coreMemoryPool, (double) (memPerCpuUsageBytes * cpuCount) / coreMemoryPool,
                    effectiveCpus, cpuSnapshots, snapshot.pCpus.get(sampleCpu));
        }


        public CpuSnapshot getCpuSnapshot(int cpuId, double cpuQuota, int coreCpuCount) {
            if (cpuId < 0 || cpuId >= perQuotaCpuPressure.length()) {
                return new CpuSnapshot(cpuId, cpuQuota, period, globalEffectiveCpus.cardinality(),
                        globalMemoryPool, (long) (globalMemoryPool * totalMemoryUtilization), 0, 0,
                        0, 0, 0, 0, snapshot.pCpus.get(cpuId));
            }

            Double stallRatioObj = perQuotaCpuPressure.get(cpuId);
            Double throttleRatioObj = perQuotaCpuThrottleRatio.get(cpuId);

            double stallRatio = (stallRatioObj == null) ? 0.0 : stallRatioObj;
            double throttleRatio = (throttleRatioObj == null) ? 0.0 : throttleRatioObj;

            double cpuPressure = 1.0 - ((1.0 - stallRatio) * (1.0 - throttleRatio));

            double memUtil = (double) memPerCpuUsageBytes / (perCpuMemoryPool * coreCpuCount);
            double io = ioPressure * 0.8;
            double combinedPressure = 1.0 - ((1.0 - cpuPressure) * (1.0 - io) * (1.0 - memUtil));

            return new CpuSnapshot(cpuId, cpuQuota, period, globalEffectiveCpus.cardinality(),
                    globalMemoryPool, (long) (globalMemoryPool * totalMemoryUtilization),
                    coreCpuCount * perCpuMemoryPool,
                    (double) memPerCpuUsageBytes / (perCpuMemoryPool * coreCpuCount), stallRatio,
                    throttleRatio, combinedPressure, timestampNs, snapshot.pCpus.get(cpuId));
        }

        // Gets the total system pressure
        public double pressure() {
            double cpu = 1.0 - (1.0 - cpuThrottleRatio);
            double io = ioPressure * 0.8;
            double pressure = Math.max(Math.max(cpu, totalMemoryUtilization), io);

            return clampDouble(pressure, 0.0, 1.0);
        }

        public HardwareUtilization clone() {
            return new HardwareUtilization(timestampNs, quotaCpus, quotaCpuUsage, period,
                    (BitSet) globalEffectiveCpus.clone(), perQuotaCpuPressure, cpuThrottleRatio,
                    perQuotaCpuThrottleRatio, globalMemoryPool, perCpuMemoryPool,
                    totalMemoryUtilization, memPerCpuUsageBytes, ioBytesPerSecond, ioPressure,
                    snapshot);
        }
    }
}
