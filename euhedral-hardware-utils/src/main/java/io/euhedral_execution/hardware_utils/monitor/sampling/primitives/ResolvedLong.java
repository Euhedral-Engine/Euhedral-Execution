package io.euhedral_execution.hardware_utils.monitor.sampling.primitives;

import io.euhedral_execution.hardware_utils.monitor.sampling.enums.SignalResolution;

public record ResolvedLong(long value, long observedAtNs, SignalResolution resolution) {
    public ResolvedLong {
        if (resolution == SignalResolution.UNAVAILABLE) {
            value = 0L;
        }
    }
}
