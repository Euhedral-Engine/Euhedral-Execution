package io.euhedral_execution.hardware_utils.internal.pressure;

import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.ATTACK_TAU_SECONDS_INV;
import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.HEADROOM_ONSET;
import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.HEADROOM_RANGE;
import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.IO_LATENCY_ONSET_NS;
import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.IO_LATENCY_RANGE_NS;
import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.IO_QUEUE_ONSET;
import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.IO_QUEUE_RANGE;
import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.RECLAIM_FULL_FRACTION;
import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.RELEASE_TAU_SECONDS_INV;
import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.RUN_QUEUE_ONSET;
import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.RUN_QUEUE_RANGE;
import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.lowPowerLoss;
import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.thermalLoss;
import static io.euhedral_execution.hardware_utils.internal.pressure.PressureConstants.unit;

import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalResolution;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.IntervalHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuIntervalSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuSlowIntervalSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.IoIntervalSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.MemoryIntervalSignals;
import java.util.BitSet;

public final class PressureEvaluator {

    private static final double NS_TO_SEC = 1.0 / 1_000_000_000;

    public static PressureEvaluation evaluate(IntervalHardwareSample sample, PressureState priorState,
            long evaluationNs) {
        PressureState state = priorState.deepCopy();
        BitSet effectiveCpus = (BitSet) sample.effectiveCpus().clone();
        int logicalSpan = sample.logicalSpan();

        double quotaCpuUsage = 0.0;
        double quotaCpus = 0.0;
        if (sample.quotaCapacityCpus().resolution() != SignalResolution.UNAVAILABLE) {
            quotaCpus = (double) sample.quotaCapacityCpus().value();
        }

        if (sample.productiveCpuNs().resolution() == SignalResolution.CURRENT ||
                sample.productiveCpuNs().resolution() == SignalResolution.CACHED) {
            double denominator = (double) sample.productiveCpuNs().elapsedNs() * quotaCpus;
            if (denominator > 0.0 && Double.isFinite(denominator)) {
                quotaCpuUsage = unit(sample.productiveCpuNs().delta() / denominator);
            }
        }

        double scopeWaitRaw = evaluateRate(sample.scopeSchedulerWaitNs().delta(),
                sample.scopeSchedulerWaitNs().elapsedNs(),
                sample.scopeSchedulerWaitNs().resolution());
        double scopePsiRaw = evaluateRate(sample.scopePsiStallNs().delta(),
                sample.scopePsiStallNs().elapsedNs(), sample.scopePsiStallNs().resolution());
        double scopeReportedRaw = 0.0;
        if (sample.scopeReportedSchedulerStallRatio().resolution()
                != SignalResolution.UNAVAILABLE) {
            scopeReportedRaw = unit(sample.scopeReportedSchedulerStallRatio().value());
        }

        double globalThrottleRaw = evaluateRate(sample.scopeQuotaThrottledNs().delta(),
                sample.scopeQuotaThrottledNs().elapsedNs(),
                sample.scopeQuotaThrottledNs().resolution());

        double systemThermalLoss = thermalLoss(sample.systemSlowSignals().thermalSeverity());
        double systemLowPowerLoss = lowPowerLoss(sample.systemSlowSignals().lowPowerMode());

        double smoothedScopeWait = smooth(state, PressureState.CELL_SCOPE_WAIT, scopeWaitRaw,
                sample.scopeSchedulerWaitNs().resolution(), evaluationNs);
        double smoothedScopePsi = smooth(state, PressureState.CELL_SCOPE_PSI, scopePsiRaw,
                sample.scopePsiStallNs().resolution(), evaluationNs);
        double smoothedScopeReported = smooth(state, PressureState.CELL_SCOPE_REPORTED,
                scopeReportedRaw, sample.scopeReportedSchedulerStallRatio().resolution(),
                evaluationNs);

        double smoothedGlobalThrottle = smooth(state, PressureState.CELL_GLOBAL_THROTTLE,
                globalThrottleRaw, sample.scopeQuotaThrottledNs().resolution(), evaluationNs);
        double smoothedSystemThermalLoss = smooth(state, PressureState.CELL_SYSTEM_THERMAL,
                systemThermalLoss, sample.systemSlowSignals().resolution(), evaluationNs);
        double smoothedSystemLowPowerLoss = smooth(state, PressureState.CELL_SYSTEM_LOW_POWER,
                systemLowPowerLoss, sample.systemSlowSignals().resolution(), evaluationNs);

        double[] perQuotaCpuPressure = new double[logicalSpan];
        double[] perQuotaCpuThrottle = new double[logicalSpan];
        double[] pressurePerCpu = new double[logicalSpan];

        CpuIntervalSignals[] cpuSignals = sample.cpuSignals();
        CpuSlowIntervalSignals[] slowSignals = sample.cpuSlowSignals();

        for (int i = 0; i < logicalSpan; i++) {
            if (!effectiveCpus.get(i)) {
                state.clearCpu(i);
                continue;
            }

            CpuIntervalSignals sig = cpuSignals[i];
            CpuSlowIntervalSignals slow = slowSignals[i];

            double waitRaw = evaluateRate(sig.schedulerWait().delta(),
                    sig.schedulerWait().elapsedNs(), sig.schedulerWait().resolution());
            double psiRaw = evaluateRate(sig.psiStall().delta(), sig.psiStall().elapsedNs(),
                    sig.psiStall().resolution());
            double reportedRaw =
                    sig.reportedSchedulerStallRatio().resolution() != SignalResolution.UNAVAILABLE
                            ? unit(sig.reportedSchedulerStallRatio().value()) : 0.0;

            double runQueueRaw = 0.0;
            if (sig.runnablePerCapacity().resolution() != SignalResolution.UNAVAILABLE) {
                runQueueRaw = unit(
                        (sig.runnablePerCapacity().value() - RUN_QUEUE_ONSET) / RUN_QUEUE_RANGE);
            }

            double schedulerRaw = max(scopeWaitRaw, scopePsiRaw, scopeReportedRaw, waitRaw, psiRaw,
                    reportedRaw, runQueueRaw);
            pressurePerCpu[i] = schedulerRaw;

            double waitSmooth = smooth(state, state.cpuCellIndex(i, PressureState.PC_WAIT), waitRaw,
                    sig.schedulerWait().resolution(), evaluationNs);
            double psiSmooth = smooth(state, state.cpuCellIndex(i, PressureState.PC_PSI), psiRaw,
                    sig.psiStall().resolution(), evaluationNs);
            double reportedSmooth = smooth(state, state.cpuCellIndex(i, PressureState.PC_REPORTED),
                    reportedRaw, sig.reportedSchedulerStallRatio().resolution(), evaluationNs);
            double runQueueSmooth = smooth(state, state.cpuCellIndex(i, PressureState.PC_RUN_QUEUE),
                    runQueueRaw, sig.runnablePerCapacity().resolution(), evaluationNs);

            double schedulerSmooth = max(smoothedScopeWait, smoothedScopePsi, smoothedScopeReported,
                    waitSmooth, psiSmooth, reportedSmooth, runQueueSmooth);

            double cpuThrottleRaw = evaluateRate(sig.quotaThrottle().delta(),
                    sig.quotaThrottle().elapsedNs(), sig.quotaThrottle().resolution());
            double cpuThrottleSmooth = smooth(state,
                    state.cpuCellIndex(i, PressureState.PC_CPU_THROTTLE), cpuThrottleRaw,
                    sig.quotaThrottle().resolution(), evaluationNs);
            double throttleSmooth = max(smoothedGlobalThrottle, cpuThrottleSmooth);
            perQuotaCpuThrottle[i] = throttleSmooth;

            double stealRaw = evaluateRate(sig.steal().delta(), sig.steal().elapsedNs(),
                    sig.steal().resolution());
            double stealSmooth = smooth(state, state.cpuCellIndex(i, PressureState.PC_STEAL),
                    stealRaw, sig.steal().resolution(), evaluationNs);
            double externalRaw =
                    sig.externalContentionRatio().resolution() != SignalResolution.UNAVAILABLE
                            ? unit(sig.externalContentionRatio().value()) : 0.0;
            double externalSmooth = smooth(state, state.cpuCellIndex(i, PressureState.PC_EXTERNAL),
                    externalRaw, sig.externalContentionRatio().resolution(), evaluationNs);
            double externalDomain = max(stealSmooth, externalSmooth);

            double capacityLossRaw = 0.0;
            if (slow.availableCapacityUnits().resolution() != SignalResolution.UNAVAILABLE
                    && slow.nominalCapacityUnits().resolution() != SignalResolution.UNAVAILABLE) {
                double nom = slow.nominalCapacityUnits().value();
                double avail = slow.availableCapacityUnits().value();
                if (nom > 0.0 && Double.isFinite(nom) && Double.isFinite(avail)) {
                    capacityLossRaw = unit(1.0 - (avail / nom));
                }
            }
            double capacityLossSmooth = smooth(state,
                    state.cpuCellIndex(i, PressureState.PC_CAPACITY_LOSS), capacityLossRaw,
                    slow.availableCapacityUnits().resolution() == SignalResolution.UNAVAILABLE
                            || slow.nominalCapacityUnits().resolution()
                            == SignalResolution.UNAVAILABLE ? SignalResolution.UNAVAILABLE
                            : SignalResolution.CURRENT, evaluationNs);

            double freqLossRaw = 0.0;
            if (slow.constrainedFrequencyHz().resolution() != SignalResolution.UNAVAILABLE
                    && slow.nominalFrequencyHz().resolution() != SignalResolution.UNAVAILABLE) {
                double nomFreq = (double) slow.nominalFrequencyHz().value();
                double freq = (double) slow.constrainedFrequencyHz().value();
                if (nomFreq > 0.0 && Double.isFinite(nomFreq) && Double.isFinite(freq)) {
                    freqLossRaw = unit(1.0 - (freq / nomFreq));
                }
            }
            double freqLossSmooth = smooth(state,
                    state.cpuCellIndex(i, PressureState.PC_FREQUENCY_LOSS), freqLossRaw,
                    slow.constrainedFrequencyHz().resolution() == SignalResolution.UNAVAILABLE
                            || slow.nominalFrequencyHz().resolution()
                            == SignalResolution.UNAVAILABLE ? SignalResolution.UNAVAILABLE
                            : SignalResolution.CURRENT, evaluationNs);

            double perCpuThermalLossRaw = thermalLoss(slow.thermalSeverity());
            double perCpuThermalSmooth = smooth(state,
                    state.cpuCellIndex(i, PressureState.PC_THERMAL), perCpuThermalLossRaw,
                    slow.resolution(), evaluationNs);

            double perCpuLowPowerLossRaw = lowPowerLoss(slow.lowPowerMode());
            double perCpuLowPowerSmooth = smooth(state,
                    state.cpuCellIndex(i, PressureState.PC_LOW_POWER), perCpuLowPowerLossRaw,
                    slow.resolution(), evaluationNs);

            double capacityDomain = max(capacityLossSmooth, freqLossSmooth, perCpuThermalSmooth,
                    smoothedSystemThermalLoss, perCpuLowPowerSmooth, smoothedSystemLowPowerLoss);

            double cpuDomain = max(schedulerSmooth, throttleSmooth, externalDomain, capacityDomain);
            perQuotaCpuPressure[i] = cpuDomain;
        }

        // Memory Domain
        MemoryIntervalSignals mem = sample.memorySignals();
        double headroomSmooth = 0.0;
        double reclaimSmooth = 0.0;
        double memoryStallSmooth = 0.0;
        double totalMemoryUtilization = 0.0;

        long L = -1L; // Unknown
        if (mem.hardLimitBytes().resolution() != SignalResolution.UNAVAILABLE
                && mem.hardLimitBytes().value() >= 0) {
            L = mem.hardLimitBytes().value();
        } else if (mem.highLimitBytes().resolution() != SignalResolution.UNAVAILABLE
                && mem.highLimitBytes().value() >= 0) {
            L = mem.highLimitBytes().value();
        }

        long usageBytes =
                mem.usageBytes().resolution() != SignalResolution.UNAVAILABLE ? Math.max(0,
                        mem.usageBytes().value()) : 0L;
        long inactiveBytes =
                mem.inactiveFileBytes().resolution() != SignalResolution.UNAVAILABLE ? Math.max(0,
                        mem.inactiveFileBytes().value()) : 0L;

        long W = Math.max(0, usageBytes - inactiveBytes);
        double U = 0.0;
        if (L == 0) {
            U = 1.0;
        } else if (L > 0) {
            U = unit((double) W / (double) L);
        }
        totalMemoryUtilization = U;

        double headroomRaw = 0.0;
        if (L >= 0) {
            headroomRaw = unit((U - HEADROOM_ONSET) / HEADROOM_RANGE);
            headroomSmooth = smooth(state, PressureState.CELL_HEADROOM, headroomRaw,
                    SignalResolution.CURRENT, evaluationNs);
        } else {
            headroomSmooth = smooth(state, PressureState.CELL_HEADROOM, 0.0,
                    SignalResolution.UNAVAILABLE, evaluationNs);
        }

        double reclaimRaw = 0.0;
        if (L > 0 && mem.cumulativeReclaimBytes().resolution() != SignalResolution.UNAVAILABLE
                && mem.cumulativeReclaimBytes().resolution() != SignalResolution.BASELINE) {
            double reclaimFrac =
                    ((double) mem.cumulativeReclaimBytes().delta() * 1_000_000_000.0) / (L
                            * mem.cumulativeReclaimBytes().elapsedNs());
            if (Double.isFinite(reclaimFrac)) {
                reclaimRaw = unit(reclaimFrac / RECLAIM_FULL_FRACTION);
            }
        }
        reclaimSmooth = smooth(state, PressureState.CELL_RECLAIM, reclaimRaw,
                L > 0 ? mem.cumulativeReclaimBytes().resolution() : SignalResolution.UNAVAILABLE,
                evaluationNs);

        double memStallRaw = evaluateRate(mem.memoryStallNs().delta(),
                mem.memoryStallNs().elapsedNs(), mem.memoryStallNs().resolution());
        memoryStallSmooth = smooth(state, PressureState.CELL_MEMORY_STALL, memStallRaw,
                mem.memoryStallNs().resolution(), evaluationNs);

        double memoryDomain = max(headroomSmooth, reclaimSmooth, memoryStallSmooth);

        // IO Domain
        IoIntervalSignals io = sample.ioSignals();
        double ioStallRaw = evaluateRate(io.stallNs().delta(), io.stallNs().elapsedNs(),
                io.stallNs().resolution());
        double ioStallSmooth = smooth(state, PressureState.CELL_IO_STALL, ioStallRaw,
                io.stallNs().resolution(), evaluationNs);

        double latencyRaw = 0.0;
        if (io.operationsLatency().resolution() != SignalResolution.UNAVAILABLE
                && io.operationsLatency().resolution() != SignalResolution.BASELINE) {
            if (io.operationsLatency().operationsDelta() > 0) {
                double avgLatencyNs =
                        (double) io.operationsLatency().latencyDelta() / io.operationsLatency()
                                .operationsDelta();
                if (Double.isFinite(avgLatencyNs)) {
                    latencyRaw = unit((avgLatencyNs - IO_LATENCY_ONSET_NS) / IO_LATENCY_RANGE_NS);
                }
            }
        }
        double latencySmooth = smooth(state, PressureState.CELL_IO_LATENCY, latencyRaw,
                io.operationsLatency().resolution(), evaluationNs);

        double queueRaw = 0.0;
        if (io.maximumQueueDepth().resolution() != SignalResolution.UNAVAILABLE) {
            queueRaw = unit((io.maximumQueueDepth().value() - IO_QUEUE_ONSET) / IO_QUEUE_RANGE);
        }
        double queueSmooth = smooth(state, PressureState.CELL_IO_QUEUE, queueRaw,
                io.maximumQueueDepth().resolution(), evaluationNs);

        double diskIOBytesPerSecond = 0.0;
        if (io.productiveBytes().resolution() != SignalResolution.UNAVAILABLE
                && io.productiveBytes().resolution() != SignalResolution.BASELINE
                && io.productiveBytes().elapsedNs() > 0) {
            double rate =
                    ((double) io.productiveBytes().delta() * 1_000_000_000.0) / io.productiveBytes()
                            .elapsedNs();
            if (Double.isFinite(rate)) {
                diskIOBytesPerSecond = rate;
            }
        }

        double ioDomain = max(ioStallSmooth, latencySmooth, queueSmooth);

        for (int i = 0; i < logicalSpan; i++) {
            if (effectiveCpus.get(i)) {
                perQuotaCpuPressure[i] = max(perQuotaCpuPressure[i], memoryDomain, ioDomain);
            }
        }

        double cpuThrottleRatio = effectiveCpus.isEmpty() ? 0.0 : smoothedGlobalThrottle;
        if (sample.scopeQuotaThrottledNs().resolution() == SignalResolution.UNAVAILABLE) {
            cpuThrottleRatio = 0.0;
            for (int i = 0; i < logicalSpan; i++) {
                if (effectiveCpus.get(i) && perQuotaCpuThrottle[i] > cpuThrottleRatio) {
                    cpuThrottleRatio = perQuotaCpuThrottle[i];
                }
            }
        }

        HardwareUtilization candidate = PressureProjection.project(
                sample, perQuotaCpuPressure, perQuotaCpuThrottle, pressurePerCpu,
                quotaCpuUsage, cpuThrottleRatio, totalMemoryUtilization, ioDomain,
                diskIOBytesPerSecond, evaluationNs
        );

        return new PressureEvaluation(state, candidate);
    }

    private static double max(double... values) {
        double max = 0.0;
        for (double v : values) {
            if (v > max) {
                max = v;
            }
        }
        return max;
    }

    private static double evaluateRate(long delta, long elapsed, SignalResolution res) {
        if (res == SignalResolution.CURRENT || res == SignalResolution.CACHED) {
            if (elapsed > 0) {
                return unit((double) delta / elapsed);
            }
        }
        return 0.0;
    }

    private static double smooth(PressureState state, int cellIndex, double input,
            SignalResolution res, long evaluationNs) {
        if (res == SignalResolution.UNAVAILABLE) {
            state.initialized[cellIndex] = false;
            state.previous[cellIndex] = 0.0;
            state.lastEvaluationNs[cellIndex] = 0L;
            return 0.0;
        }

        long dtNs = evaluationNs - state.lastEvaluationNs[cellIndex];
        if (!state.initialized[cellIndex]) {
            state.previous[cellIndex] = unit(input);
            state.initialized[cellIndex] = true;
            state.lastEvaluationNs[cellIndex] = evaluationNs;
            return state.previous[cellIndex];
        }

        if (dtNs <= 0) {
            return state.previous[cellIndex];
        }

        double tau =
                (input >= state.previous[cellIndex]) ? ATTACK_TAU_SECONDS_INV
                        : RELEASE_TAU_SECONDS_INV;
        double alpha = unit(-StrictMath.expm1(-(dtNs * NS_TO_SEC) * tau));
        double next = unit(state.previous[cellIndex] + (input - state.previous[cellIndex]) * alpha);

        state.previous[cellIndex] = next;
        state.lastEvaluationNs[cellIndex] = evaluationNs;

        return next;
    }

    private PressureEvaluator() {
    }
}
