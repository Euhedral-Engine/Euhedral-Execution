package io.euhedral_execution.core.utils;

import io.euhedral_execution.core.flow_control.UpstreamQueue;

/// Maintains an average flow measurement using fixed-point EWMA.
public final class AverageFlow {

    static final int DIVISOR = 16;

    private long value;
    private boolean initialized;

    /// Records one bounded value without clocks or floating-point work.
    public void record(long sample) {
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

    /// Returns whether at least one recording has been made since reset.
    public boolean initialized() {
        return this.initialized;
    }

    /// Returns the current fixed-point EWMA; callers must check [initialized()] before interpretation.
    public long value() {
        return this.value;
    }

    /// Clears both the fixed-point value and its validity.
    public void reset() {
        this.value = 0L;
        this.initialized = false;
    }
}
