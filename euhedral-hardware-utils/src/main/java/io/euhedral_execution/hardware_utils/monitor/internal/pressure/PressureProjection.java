package io.euhedral_execution.hardware_utils.monitor.internal.pressure;

import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.monitor.sampling.enums.SignalResolution;
import io.euhedral_execution.hardware_utils.monitor.sampling.samples.IntervalHardwareSample;
import io.euhedral_execution.hardware_utils.util.UnmodifiableBitSet;
import java.util.BitSet;

final class PressureProjection {

    private PressureProjection() {}

    static HardwareUtilization project(
            IntervalHardwareSample sample,
            double[] perQuotaCpuPressure,
            double[] perQuotaCpuThrottle,
            double[] pressurePerCpu,
            double quotaCpuUsage,
            double cpuThrottleRatio,
            double memoryUtilization,
            double diskIOPressure,
            double diskIOBytesPerSecond,
            long evaluationNs) {

        int logicalSpan = sample.logicalSpan();
        BitSet effectiveCpus = (BitSet) sample.effectiveCpus().clone();

        long quotaCpusL;
        if (sample.quotaCapacityCpus().resolution() != SignalResolution.UNAVAILABLE) {
            quotaCpusL = sample.quotaCapacityCpus().value();
        } else {
            quotaCpusL = effectiveCpus.cardinality();
        }
        double quotaCpus = quotaCpusL;

        long period = 0L;
        if (sample.quotaPeriodNs().resolution() != SignalResolution.UNAVAILABLE) {
            period = sample.quotaPeriodNs().value();
        }

        long memoryLimit = Long.MAX_VALUE;
        if (sample.memorySignals().hardLimitBytes().resolution() != SignalResolution.UNAVAILABLE) {
            memoryLimit = sample.memorySignals().hardLimitBytes().value();
        } else if (sample.memorySignals().highLimitBytes().resolution() != SignalResolution.UNAVAILABLE) {
            memoryLimit = sample.memorySignals().highLimitBytes().value();
        }

        long memoryUsage = 0L;
        if (sample.memorySignals().usageBytes().resolution() != SignalResolution.UNAVAILABLE) {
            memoryUsage = sample.memorySignals().usageBytes().value();
        }

        long inactiveFile = 0L;
        if (sample.memorySignals().inactiveFileBytes().resolution() != SignalResolution.UNAVAILABLE) {
            inactiveFile = sample.memorySignals().inactiveFileBytes().value();
        }

        SystemSnapshot snapshot = SystemSnapshot.create(
                evaluationNs,
                logicalSpan,
                quotaCpus,
                period,
                0L,
                0L,
                new UnmodifiableBitSet(effectiveCpus),
                pressurePerCpu,
                new long[] {memoryLimit, memoryUsage, inactiveFile},
                0L);

        return HardwareUtilization.create(
                evaluationNs,
                quotaCpus,
                quotaCpuUsage,
                period,
                new UnmodifiableBitSet(effectiveCpus),
                cpuThrottleRatio,
                perQuotaCpuThrottle,
                perQuotaCpuPressure,
                memoryLimit,
                0L, // perCpuMemoryPool
                memoryUtilization,
                0L, // memPerCpuUsageBytes
                diskIOBytesPerSecond,
                diskIOPressure,
                snapshot);
    }
}
