package io.euhedral_execution.core.control_plane;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/// Owner-thread policy for choosing direct or staged execution and a bounded batch size.
///
/// All fields use plain access because one pinned fragment thread owns the policy for its lifetime.
final class FragmentControlPolicy {

    private static final AtomicReference<DiagnosticOverride> DIAGNOSTIC_OVERRIDE = new AtomicReference<>();

    static final long DIRECT_TARGET_BATCH_WORK_NS = 250_000L;
    static final long STAGED_TARGET_BATCH_WORK_NS = 8_000_000L;
    static final double STAGED_THRESHOLD_NS = 4_000.0;
    static final double DIRECT_THRESHOLD_NS = 2_000.0;
    static final int TRANSITION_BATCHES = 8;
    static final int SPIN_MISSES = 64;

    private final DiagnosticOverride diagnosticOverride;
    private Mode mode;
    private long batchSize;
    private double serviceTimeNs;
    private int transitionStreak;
    private int activeMissStreak;

    /// Creates a policy, capturing any setup-only diagnostic override before owner-thread use.
    FragmentControlPolicy() {
        this.diagnosticOverride = DIAGNOSTIC_OVERRIDE.getAcquire();
        reset();
    }

    /// Installs one process-local diagnostic override before benchmark fragments are constructed.
    static DiagnosticOverride installDiagnosticOverride(Mode mode, long batchSize) {
        DiagnosticOverride next = new DiagnosticOverride(mode, batchSize);
        DiagnosticOverride witness = DIAGNOSTIC_OVERRIDE.compareAndExchangeRelease(null, next);
        if (witness != null) {
            throw new IllegalStateException("A fragment diagnostic override is already installed");
        }
        return next;
    }

    /// Clears the exact diagnostic override after all fragments that captured it have closed.
    static void clearDiagnosticOverride(DiagnosticOverride expected) {
        Objects.requireNonNull(expected);
        DiagnosticOverride witness = DIAGNOSTIC_OVERRIDE.compareAndExchangeRelease(expected, null);
        if (witness != expected) {
            throw new IllegalStateException("The fragment diagnostic override changed before cleanup");
        }
    }

    /// Records one aggregate execution sample in nanoseconds across `frames` completed frames.
    void recordExecution(long elapsedNs, long frames) {
        if (elapsedNs <= 0L || frames <= 0L) {
            return;
        }
        double sample = (double) elapsedNs / frames;
        if (!Double.isFinite(sample) || sample <= 0.0) {
            return;
        }
        if (this.serviceTimeNs == 0.0) {
            this.serviceTimeNs = sample;
        } else {
            this.serviceTimeNs += (sample - this.serviceTimeNs) / 8.0;
        }
    }

    /// Completes a productive batch and returns the next batch within `eligibleCap`.
    long completeBatch(long eligibleCap) {
        if (this.diagnosticOverride != null) {
            this.mode = this.diagnosticOverride.mode();
            this.transitionStreak = 0;
            long cap = Math.max(2L, eligibleCap);
            this.batchSize = Math.max(2L, Math.min(this.diagnosticOverride.batchSize(), cap));
            return this.batchSize;
        }

        updateMode();

        long cap = Math.max(2L, eligibleCap);
        long desired = this.batchSize;
        if (this.serviceTimeNs > 0.0) {
            long workTarget = this.mode == Mode.DIRECT ? DIRECT_TARGET_BATCH_WORK_NS : STAGED_TARGET_BATCH_WORK_NS;
            long raw = (long) Math.floor(workTarget / Math.max(this.serviceTimeNs, 1.0));
            raw = Math.max(2L, raw);
            desired = Math.max(2L, Long.highestOneBit(raw));
        }
        desired = Math.min(desired, cap);

        long minimum = (this.batchSize >>> 1) + (this.batchSize & 1L);
        long maximum = saturatingDouble(this.batchSize);
        long next = Math.max(minimum, Math.min(desired, maximum));
        this.batchSize = Math.max(2L, Math.min(next, cap));
        return this.batchSize;
    }

    /// Records an active-source/cache miss and reports when bounded parking should replace spinning.
    boolean missRequiresPark() {
        if (this.activeMissStreak <= SPIN_MISSES) {
            this.activeMissStreak++;
        }
        return this.activeMissStreak > SPIN_MISSES;
    }

    /// Clears the active miss streak after any productive execution cycle.
    void recordProgress() {
        this.activeMissStreak = 0;
    }

    /// Restores the captured initial mode, batch two, and empty timing and hysteresis state.
    void reset() {
        this.mode = this.diagnosticOverride == null ? Mode.DIRECT : this.diagnosticOverride.mode();
        this.batchSize = 2L;
        this.serviceTimeNs = 0.0;
        this.transitionStreak = 0;
        this.activeMissStreak = 0;
    }

    /// Returns the current owner-thread execution mode.
    Mode mode() {
        return this.mode;
    }

    /// Returns the current owner-thread batch size.
    long batchSize() {
        return this.batchSize;
    }

    /// Returns the current EWMA service estimate in nanoseconds per frame, or zero before sampling.
    double serviceTimeNs() {
        return this.serviceTimeNs;
    }

    /// Returns the consecutive completed-batch count toward the active mode transition.
    int transitionStreak() {
        return this.transitionStreak;
    }

    /// Doubles a positive batch limit without signed overflow.
    static long saturatingDouble(long value) {
        return value > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : value * 2L;
    }

    /// Applies completed-batch hysteresis without changing mode between boundaries.
    private void updateMode() {
        boolean transitionRegion = this.mode == Mode.DIRECT
                ? this.serviceTimeNs >= STAGED_THRESHOLD_NS
                : this.serviceTimeNs > 0.0 && this.serviceTimeNs <= DIRECT_THRESHOLD_NS;
        if (!transitionRegion) {
            this.transitionStreak = 0;
            return;
        }

        this.transitionStreak++;
        if (this.transitionStreak == TRANSITION_BATCHES) {
            this.mode = this.mode == Mode.DIRECT ? Mode.STAGED : Mode.DIRECT;
            this.transitionStreak = 0;
        }
    }

    /// Execution strategies selected only at completed-batch boundaries.
    enum Mode {
        DIRECT,
        STAGED
    }

    /// Immutable setup-only mode and batch target captured by diagnostic benchmark policies.
    record DiagnosticOverride(Mode mode, long batchSize) {

        DiagnosticOverride {
            Objects.requireNonNull(mode);
            if (batchSize < 2L) {
                throw new IllegalArgumentException("Diagnostic batch size must be at least two");
            }
        }
    }
}
