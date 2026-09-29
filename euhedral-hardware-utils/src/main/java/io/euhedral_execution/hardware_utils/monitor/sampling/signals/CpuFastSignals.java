package io.euhedral_execution.hardware_utils.monitor.sampling.signals;

import io.euhedral_execution.hardware_utils.monitor.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.DoubleGaugeSignal;

public record CpuFastSignals(
        CounterSignal schedulerWait,
        CounterSignal psiStall,
        DoubleGaugeSignal reportedSchedulerStallRatio,
        CounterSignal quotaThrottle,
        CounterSignal steal,
        DoubleGaugeSignal externalContentionRatio,
        DoubleGaugeSignal runnablePerCapacity) {
    public CpuFastSignals {
        if (reportedSchedulerStallRatio != null && reportedSchedulerStallRatio.validity() == SignalValidity.VALID) {
            if (reportedSchedulerStallRatio.value() > 1.0) {
                reportedSchedulerStallRatio = new DoubleGaugeSignal(
                        0.0, reportedSchedulerStallRatio.observedAtNs(), SignalValidity.TRANSIENT_FAILURE);
            }
        }
        if (externalContentionRatio != null && externalContentionRatio.validity() == SignalValidity.VALID) {
            if (externalContentionRatio.value() > 1.0) {
                externalContentionRatio = new DoubleGaugeSignal(
                        0.0, externalContentionRatio.observedAtNs(), SignalValidity.TRANSIENT_FAILURE);
            }
        }
    }
}
