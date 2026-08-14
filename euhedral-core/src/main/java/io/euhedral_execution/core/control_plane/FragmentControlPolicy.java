package io.euhedral_execution.core.control_plane;

import java.util.BitSet;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/// Owner-thread policy for choosing direct or staged execution and a bounded batch size.
///
/// All fields use plain access because one pinned fragment thread owns the policy for its lifetime.
final class FragmentControlPolicy {

    private static final AtomicReference<DiagnosticOverride> DIAGNOSTIC_OVERRIDE = new AtomicReference<>();

    static final long DIRECT_TARGET_BATCH_WORK_NS = 250_000L;
    static final long STAGED_TARGET_BATCH_WORK_NS = 8_000_000L;
    static final double EXTREMELY_CHEAP_BODY_COST_MAX_NS = 20.0;
    static final double CHEAP_BODY_COST_MAX_NS = 90.0;
    static final double EXPENSIVE_BODY_COST_MIN_NS = 95.0;
    // Calibration-host candidate: Phase 14 left a gap between 582k DIRECT and 705k high contention.
    static final long LOW_ACQUIRE_CONTENTION_MAX = 650_000L;
    static final String ACQUIRE_CONTENTION_SELECTION_ENABLED_PROPERTY =
            "euhedral.fragment.acquireContention.selection.enabled";
    static final String HIGH_CONTENTION_IDLE_THRESHOLD_PROPERTY = "euhedral.fragment.highContentionIdle.threshold";
    static final String HIGH_CONTENTION_PARK_NANOS_PROPERTY = "euhedral.fragment.highContentionIdle.parkNanos";
    static final String HIGH_CONTENTION_IDLE_BODY_COST_MAX_NS_PROPERTY =
            "euhedral.fragment.highContentionIdle.bodyCostMaxNs";
    static final long DEFAULT_HIGH_CONTENTION_IDLE_THRESHOLD = 980_000L;
    static final long DEFAULT_HIGH_CONTENTION_PARK_NANOS = 15_000L;
    static final double DEFAULT_HIGH_CONTENTION_IDLE_BODY_COST_MAX_NS = 200.0;
    static final int BODY_COST_WINDOW_SAMPLES = 32;
    static final int BODY_COST_MIN_HISTORY = 32;
    static final int EXPENSIVE_CONFIRMATION_WINDOWS = 2;
    static final int EXPENSIVE_CONFIRMATION_SAMPLES = BODY_COST_WINDOW_SAMPLES * EXPENSIVE_CONFIRMATION_WINDOWS;
    static final int SPIN_MISSES = 64;

    private static final boolean ACQUIRE_CONTENTION_SELECTION_ENABLED = Boolean.parseBoolean(
            System.getProperty(ACQUIRE_CONTENTION_SELECTION_ENABLED_PROPERTY, Boolean.TRUE.toString()));
    static final long HIGH_CONTENTION_IDLE_THRESHOLD = readHighContentionIdleThreshold();
    static final long HIGH_CONTENTION_PARK_NANOS = readHighContentionParkNanos();
    static final double HIGH_CONTENTION_IDLE_BODY_COST_MAX_NS = readHighContentionIdleBodyCostMaxNs();

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
        DiagnosticOverride next =
                new DiagnosticOverride(Objects.requireNonNull(mode), batchSize, bodyCostSampling, null);
        return installDiagnosticOverride(next);
    }

    /// Installs normal selection with a fixed benchmark-only set of polling cores.
    static DiagnosticOverride installDiagnosticPollingOverride(BitSet pollingCores) {
        Objects.requireNonNull(pollingCores);
        if (pollingCores.isEmpty()) {
            throw new IllegalArgumentException("At least one diagnostic core must remain active");
        }
        return installDiagnosticOverride(new DiagnosticOverride(null, 2L, true, pollingCores));
    }

    /// Publishes one setup-only override into the process-local slot.
    private static DiagnosticOverride installDiagnosticOverride(DiagnosticOverride next) {
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
        return completeBatch(eligibleCap, productiveHandles, registeredWorkers, -1L);
    }

    /// Completes a batch using one owner-local fixed-point contention read or `-1` before bootstrap.
    long completeBatch(long eligibleCap, long productiveHandles, int registeredWorkers, long acquisitionContention) {
        if (this.diagnosticOverride != null && this.diagnosticOverride.mode() != null) {
            this.mode = this.diagnosticOverride.mode();
            long cap = Math.max(2L, eligibleCap);
            this.batchSize = Math.max(2L, Math.min(this.diagnosticOverride.batchSize(), cap));
            return this.batchSize;
        }

        this.mode = selectMode(
                productiveHandles,
                registeredWorkers,
                this.bodyCostHistoryCount,
                this.smoothedBodyCostNs,
                acquisitionContention,
                this.mode,
                ACQUIRE_CONTENTION_SELECTION_ENABLED);

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
        this.mode = this.diagnosticOverride == null || this.diagnosticOverride.mode() == null
                ? Mode.DIRECT
                : this.diagnosticOverride.mode();
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

    /// Reports the startup-fixed comparison setting used by normal production selection.
    static boolean acquireContentionSelectionEnabled() {
        return ACQUIRE_CONTENTION_SELECTION_ENABLED;
    }

    /// Reports whether this worker belongs to a setup-only fixed polling subset.
    boolean activePollingAllowed(int core) {
        return this.diagnosticOverride == null || this.diagnosticOverride.allowsPolling(core);
    }

    /// Selects excess-worker idling without changing the independently settled execution mode.
    boolean idleEligible(long productiveHandles, int registeredWorkers, int workerRank) {
        if (this.diagnosticOverride != null) {
            return false;
        }
        return selectIdleEligibility(
                productiveHandles, registeredWorkers, workerRank, this.bodyCostHistoryCount, this.smoothedBodyCostNs);
    }

    /// Selects one finite contention park while rank zero remains a deterministic poller.
    boolean highContentionIdleEligible(long acquisitionContention, int registeredWorkers, int workerRank) {
        if (this.diagnosticOverride != null || HIGH_CONTENTION_IDLE_THRESHOLD < 0L) {
            return false;
        }
        return selectHighContentionIdleEligibility(
                acquisitionContention,
                registeredWorkers,
                workerRank,
                this.bodyCostHistoryCount,
                this.smoothedBodyCostNs,
                HIGH_CONTENTION_IDLE_THRESHOLD,
                HIGH_CONTENTION_IDLE_BODY_COST_MAX_NS);
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

    /// Selects the production path from availability, body cost, and fixed-point contention.
    static Mode selectMode(
            long productiveHandles,
            int registeredWorkers,
            int bodyCostHistoryCount,
            double smoothedBodyCostNs,
            long acquisitionContention,
            Mode currentSettledMode,
            boolean acquisitionContentionSelectionEnabled) {
        if (!acquisitionContentionSelectionEnabled || acquisitionContention < 0L) {
            return selectMode(
                    productiveHandles, registeredWorkers, bodyCostHistoryCount, smoothedBodyCostNs, currentSettledMode);
        }
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
        if (acquisitionContention <= LOW_ACQUIRE_CONTENTION_MAX) {
            return Mode.DIRECT;
        }
        if (smoothedBodyCostNs >= EXPENSIVE_BODY_COST_MIN_NS) {
            return Mode.STAGED;
        }
        return currentSettledMode;
    }

    /// Selects only the measured extreme-cheap excess capacity while rank zero always polls.
    static boolean selectIdleEligibility(
            long productiveHandles,
            int registeredWorkers,
            int workerRank,
            int bodyCostHistoryCount,
            double smoothedBodyCostNs) {
        if (registeredWorkers <= 1 || workerRank < 0 || workerRank >= registeredWorkers) {
            return false;
        }
        if (productiveHandles >= registeredWorkers || bodyCostHistoryCount < BODY_COST_MIN_HISTORY) {
            return false;
        }
        if (smoothedBodyCostNs <= 0.0 || smoothedBodyCostNs > EXTREMELY_CHEAP_BODY_COST_MAX_NS) {
            return false;
        }
        long pollingQuota = Math.max(1L, Math.min(productiveHandles, registeredWorkers));
        return workerRank >= pollingQuota;
    }

    /// Tests the independent high-contention branch inside one configured light-body range.
    static boolean selectHighContentionIdleEligibility(
            long acquisitionContention,
            int registeredWorkers,
            int workerRank,
            int bodyCostHistoryCount,
            double bodyCostNs,
            long threshold,
            double bodyCostMaxNs) {
        if (threshold < 0L
                || threshold > 1_000_000L
                || !Double.isFinite(bodyCostNs)
                || !Double.isFinite(bodyCostMaxNs)
                || bodyCostMaxNs <= EXTREMELY_CHEAP_BODY_COST_MAX_NS) {
            return false;
        }
        if (acquisitionContention < 0L || acquisitionContention < threshold) {
            return false;
        }
        if (bodyCostHistoryCount < BODY_COST_MIN_HISTORY
                || bodyCostNs <= EXTREMELY_CHEAP_BODY_COST_MAX_NS
                || bodyCostNs > bodyCostMaxNs) {
            return false;
        }
        return registeredWorkers > 1 && workerRank > 0 && workerRank < registeredWorkers;
    }

    /// Reads the startup-fixed fixed-point threshold, accepting `disabled` for comparison forks.
    private static long readHighContentionIdleThreshold() {
        String raw = System.getProperty(
                HIGH_CONTENTION_IDLE_THRESHOLD_PROPERTY, Long.toString(DEFAULT_HIGH_CONTENTION_IDLE_THRESHOLD));
        if ("disabled".equalsIgnoreCase(raw)) {
            return -1L;
        }
        long value = Long.parseLong(raw);
        if (value < 0L || value > 1_000_000L) {
            throw new IllegalArgumentException("High-contention idle threshold must be disabled or in [0, 1000000]");
        }
        return value;
    }

    /// Reads the startup-fixed finite park duration used only by eligible worker ranks.
    private static long readHighContentionParkNanos() {
        long value = Long.parseLong(System.getProperty(
                HIGH_CONTENTION_PARK_NANOS_PROPERTY, Long.toString(DEFAULT_HIGH_CONTENTION_PARK_NANOS)));
        if (value <= 0L) {
            throw new IllegalArgumentException("High-contention park duration must be positive");
        }
        return value;
    }

    /// Reads the startup-fixed light-body ceiling used by the developer policy override.
    private static double readHighContentionIdleBodyCostMaxNs() {
        double value = Double.parseDouble(System.getProperty(
                HIGH_CONTENTION_IDLE_BODY_COST_MAX_NS_PROPERTY,
                Double.toString(DEFAULT_HIGH_CONTENTION_IDLE_BODY_COST_MAX_NS)));
        if (!Double.isFinite(value) || value <= EXTREMELY_CHEAP_BODY_COST_MAX_NS) {
            throw new IllegalArgumentException("High-contention idle body-cost maximum must exceed 20 ns");
        }
        return value;
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
    record DiagnosticOverride(Mode mode, long batchSize, boolean bodyCostSampling, BitSet pollingCores) {

        DiagnosticOverride {
            if (batchSize < 2L) {
                throw new IllegalArgumentException("Diagnostic batch size must be at least two");
            }
            pollingCores = pollingCores == null ? null : (BitSet) pollingCores.clone();
        }

        /// Creates the compatibility form with production body-cost sampling disabled.
        DiagnosticOverride(Mode mode, long batchSize) {
            this(Objects.requireNonNull(mode), batchSize, false, null);
        }

        /// Returns an isolated copy of the optional polling-core mask.
        @Override
        public BitSet pollingCores() {
            return this.pollingCores == null ? null : (BitSet) this.pollingCores.clone();
        }

        /// Tests one core against the captured polling mask.
        boolean allowsPolling(int core) {
            return this.pollingCores == null || this.pollingCores.get(core);
        }
    }
}
