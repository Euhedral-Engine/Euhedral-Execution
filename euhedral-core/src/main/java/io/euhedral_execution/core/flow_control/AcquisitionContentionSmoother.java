package io.euhedral_execution.core.flow_control;

/// Maintains one worker-owned, update-based fixed-point acquisition-contention EWMA.
final class AcquisitionContentionSmoother {

    static final int DIVISOR = 16;

    private long value;
    private boolean initialized;

    /// Records one bounded pull-cycle contention fraction without clocks or floating-point work.
    void record(long sample) {
        if (sample < 0L || sample > UpstreamQueue.ACQUIRE_CONTENTION_SCALE) {
            throw new IllegalArgumentException("Acquisition contention sample is outside the fixed-point range");
        }
        if (!this.initialized) {
            this.value = sample;
            this.initialized = true;
            return;
        }
        this.value += (sample - this.value) / DIVISOR;
    }

    /// Returns whether at least one eligible pull cycle has been observed since reset.
    boolean initialized() {
        return this.initialized;
    }

    /// Returns the current fixed-point EWMA; callers must check [initialized()] before interpretation.
    long value() {
        return this.value;
    }

    /// Clears both the fixed-point value and its validity for the next worker lifecycle trial.
    void reset() {
        this.value = 0L;
        this.initialized = false;
    }
}
