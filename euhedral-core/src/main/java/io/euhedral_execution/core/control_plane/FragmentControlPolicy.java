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
    static final double CHEAP_BODY_COST_MAX_NS = 90.0;
    static final double EXPENSIVE_BODY_COST_MIN_NS = 95.0;
    static final int BODY_COST_WINDOW_SAMPLES = 32;
    static final int BODY_COST_MIN_HISTORY = 32;
    static final int EXPENSIVE_CONFIRMATION_WINDOWS = 2;
    static final int EXPENSIVE_CONFIRMATION_SAMPLES = BODY_COST_WINDOW_SAMPLES * EXPENSIVE_CONFIRMATION_WINDOWS;
    static final int SPIN_MISSES = 64;

    private final DiagnosticOverride diagnosticOverride;
    private Mode mode;
    private long batchSize;
    private double serviceTimeNs;
    private final double[] bodyCostWindow = new double[BODY_COST_WINDOW_SAMPLES];
    private double smoothedBodyCostNs;
    private int bodyCostHistoryCount;
    private int bodyCostWindowIndex;
    private int expensiveConfirmationWindows;
    private int activeMissStreak;

    /// Creates a policy, capturing any setup-only diagnostic override before owner-thread use.
    FragmentControlPolicy() {
        this.diagnosticOverride = DIAGNOSTIC_OVERRIDE.getAcquire();
        reset();
    }

    /// Installs one process-local diagnostic override before benchmark fragments are constructed.
    static DiagnosticOverride installDiagnosticOverride(Mode mode, long batchSize) {
        return installDiagnosticOverride(mode, batchSize, false);
    }

    /// Installs a forced mode with optional production-estimator sampling for diagnostics.
    static DiagnosticOverride installDiagnosticOverride(Mode mode, long batchSize, boolean bodyCostSampling) {
        DiagnosticOverride next = new DiagnosticOverride(mode, batchSize, bodyCostSampling);
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

    /// Records one successful sparse executor-body sample into the owner-local estimate.
    void recordBodyCost(long elapsedNs) {
        if (elapsedNs <= 0L) {
            return;
        }
        double sample = elapsedNs;
        if (this.bodyCostHistoryCount < BODY_COST_WINDOW_SAMPLES) {
            this.bodyCostWindow[this.bodyCostHistoryCount] = sample;
            this.bodyCostHistoryCount++;
            if (this.bodyCostHistoryCount == BODY_COST_WINDOW_SAMPLES) {
                updateBodyCostEstimate();
            }
            return;
        }

        this.bodyCostWindow[this.bodyCostWindowIndex] = sample;
        this.bodyCostWindowIndex = (this.bodyCostWindowIndex + 1) % BODY_COST_WINDOW_SAMPLES;
        if (this.bodyCostHistoryCount < Integer.MAX_VALUE) {
            this.bodyCostHistoryCount++;
        }
        if (this.bodyCostWindowIndex == 0) {
            updateBodyCostEstimate();
        }
    }

    /// Completes a productive batch and returns the next batch within `eligibleCap`.
    long completeBatch(long eligibleCap, long productiveHandles, int registeredWorkers) {
        if (this.diagnosticOverride != null) {
            this.mode = this.diagnosticOverride.mode();
            long cap = Math.max(2L, eligibleCap);
            this.batchSize = Math.max(2L, Math.min(this.diagnosticOverride.batchSize(), cap));
            return this.batchSize;
        }

        this.mode = selectMode(
                productiveHandles, registeredWorkers, this.bodyCostHistoryCount, this.smoothedBodyCostNs, this.mode);

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
        this.smoothedBodyCostNs = 0.0;
        this.bodyCostHistoryCount = 0;
        this.bodyCostWindowIndex = 0;
        this.expensiveConfirmationWindows = 0;
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

    /// Returns the number of valid owner-local executor-body samples, saturated at integer max.
    int bodyCostHistoryCount() {
        return this.bodyCostHistoryCount;
    }

    /// Returns the owner-local executor-body estimate, or zero before one complete sample window.
    double smoothedBodyCostNs() {
        return this.smoothedBodyCostNs;
    }

    /// Reports whether this policy should attach the production body-cost sensor during setup.
    boolean bodyCostSamplingEnabled() {
        return this.diagnosticOverride == null || this.diagnosticOverride.bodyCostSampling();
    }

    /// Doubles a positive batch limit without signed overflow.
    static long saturatingDouble(long value) {
        return value > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : value * 2L;
    }

    /// Selects the explicit fragment path from availability, body history, and settled mode.
    static Mode selectMode(
            long productiveHandles,
            int registeredWorkers,
            int bodyCostHistoryCount,
            double smoothedBodyCostNs,
            Mode currentSettledMode) {
        Objects.requireNonNull(currentSettledMode);
        if (registeredWorkers <= 0 || productiveHandles >= registeredWorkers) {
            return Mode.DIRECT;
        }
        if (bodyCostHistoryCount < BODY_COST_MIN_HISTORY) {
            return Mode.DIRECT;
        }
        if (smoothedBodyCostNs <= CHEAP_BODY_COST_MAX_NS) {
            return Mode.DIRECT;
        }
        if (smoothedBodyCostNs >= EXPENSIVE_BODY_COST_MIN_NS) {
            return Mode.STAGED;
        }
        return currentSettledMode;
    }

    /// Updates one non-overlapping second minimum and confirms expensive work across two windows.
    private void updateBodyCostEstimate() {
        double minimum = Double.POSITIVE_INFINITY;
        double secondMinimum = Double.POSITIVE_INFINITY;
        for (double sample : this.bodyCostWindow) {
            if (sample < minimum) {
                secondMinimum = minimum;
                minimum = sample;
            } else if (sample < secondMinimum) {
                secondMinimum = sample;
            }
        }
        if (secondMinimum >= EXPENSIVE_BODY_COST_MIN_NS) {
            if (this.expensiveConfirmationWindows < EXPENSIVE_CONFIRMATION_WINDOWS) {
                this.expensiveConfirmationWindows++;
            }
            if (this.expensiveConfirmationWindows == EXPENSIVE_CONFIRMATION_WINDOWS) {
                this.smoothedBodyCostNs = secondMinimum;
            }
            return;
        }
        this.expensiveConfirmationWindows = 0;
        this.smoothedBodyCostNs = secondMinimum;
    }

    /// Execution strategies selected only at completed-batch boundaries.
    enum Mode {
        DIRECT,
        STAGED
    }

    /// Immutable setup-only mode and batch target captured by diagnostic benchmark policies.
    record DiagnosticOverride(Mode mode, long batchSize, boolean bodyCostSampling) {

        DiagnosticOverride {
            Objects.requireNonNull(mode);
            if (batchSize < 2L) {
                throw new IllegalArgumentException("Diagnostic batch size must be at least two");
            }
        }

        /// Creates the compatibility form with production body-cost sampling disabled.
        DiagnosticOverride(Mode mode, long batchSize) {
            this(mode, batchSize, false);
        }
    }
}
