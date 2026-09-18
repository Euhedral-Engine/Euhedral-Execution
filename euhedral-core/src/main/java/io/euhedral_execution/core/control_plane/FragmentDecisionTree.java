package io.euhedral_execution.core.control_plane;

import io.euhedral_execution.core.config.IdlePolicy;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath;
import io.euhedral_execution.core.flow_control.UpstreamQueue;
import io.euhedral_execution.core.utils.MicroCalibrator;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Owner-thread policy for choosing direct or staged execution and a bounded batch size.
///
/// All fields use plain access because one pinned fragment thread owns the policy for its lifetime.
final class FragmentDecisionTree {
    static final long CONTENTION_THRESHOLD = 850_000; // 85%

    static final int EXPENSIVE_BODY_WEIGHT =
            Integer.parseInt(System.getProperty("euhedral.expensive.body.weight", "288"));
    static final int DIRECT_EXECUTION_BODY_WEIGHT =
            Integer.parseInt(System.getProperty("euhedral.direct.exec.bw", "272"));

    static final long DIRECT_BATCH_WORK_TARGET_NS = 250_000L;
    static final long STAGED_BATCH_WORK_TARGET_NS = 8_000_000L;

    // Body-cost estimator
    static final int BODY_COST_WINDOW_SAMPLES = 32;
    static final int BODY_COST_WINDOW_MASK = BODY_COST_WINDOW_SAMPLES - 1;
    static final int BODY_COST_MIN_HISTORY = 32;
    static final int EXPENSIVE_CONFIRMATION_WINDOWS = 2;

    private final IdlePolicy idlePolicy;

    private final long bodyCostDirectThreshold;

    private final long expensiveBodyCostThreshold;
    private final double[] bodyCostWindow = new double[BODY_COST_WINDOW_SAMPLES];
    private ExecutionPath executionPath;
    private long batchSize;
    private double serviceTimeNs;
    private double smoothedBodyCostNs;
    private int bodyCostHistoryCount;
    private int bodyCostWindowIndex;
    private int expensiveConfirmationWindows;

    FragmentDecisionTree(@NonNull IdlePolicy idlePolicy) {
        this.idlePolicy = Objects.requireNonNull(idlePolicy);

        MicroCalibrator calibrator = new MicroCalibrator();
        calibrator.warmup();
        this.expensiveBodyCostThreshold = calibrator.benchmark(EXPENSIVE_BODY_WEIGHT);
        this.bodyCostDirectThreshold = calibrator.benchmark(DIRECT_EXECUTION_BODY_WEIGHT);
        reset();
    }

    /// Deterministic construction seam for policy-boundary tests; production calibrates per worker.
    FragmentDecisionTree(
            @NonNull IdlePolicy idlePolicy, long expensiveBodyCostThreshold, long bodyCostDirectThreshold) {
        this.idlePolicy = Objects.requireNonNull(idlePolicy);
        this.expensiveBodyCostThreshold = expensiveBodyCostThreshold;
        this.bodyCostDirectThreshold = bodyCostDirectThreshold;
        reset();
    }

    /// Doubles a positive batch limit without signed overflow.
    static long saturatingDouble(long value) {
        return value > (Long.MAX_VALUE >>> 1) ? Long.MAX_VALUE : value << 1;
    }

    boolean shouldIdle(long contention, long productiveHandles, int registeredWorkers, int workerRank) {
        if (workerRank <= 1
                || registeredWorkers <= 1
                || this.bodyCostHistoryCount < BODY_COST_MIN_HISTORY
                || hasPlentifulProductiveHandles(productiveHandles, registeredWorkers)) {
            return false;
        }
        if (productiveHandles <= 0) {
            return true;
        }
        return ParticipationLogisticModel.shouldIdle(
                workerRank, productiveHandles, registeredWorkers, this.smoothedBodyCostNs, contention / 1_000_000.0);
    }

    long updateIdleTimingAndGetParkNanos(
            UpstreamQueue upstream, long now, long registeredWorkers, long productiveHandleCount) {
        var function = this.idlePolicy.function();

        long contention = upstream.getAdaptiveContention(now, contentionHalfLifeNanos());
        double c = contention / 1_000_000.0;
        double p = registeredWorkers > 0 ? (double) productiveHandleCount / registeredWorkers : Double.NaN;
        double body = this.smoothedBodyCostNs;
        long park = function.parkNanos(c, p, body, idleParkNs());
        long halfLife = function.halfLifeNanos(c, p, body, contentionHalfLifeNanos());
        upstream.installContentionHalfLife(now, halfLife, contentionHalfLifeNanos());
        return park;
    }

    ExecutionPath selectExecutionPath(
            long productiveHandles, long upstreamHandles, int registeredWorkers, long contention) {
        if (upstreamHandles <= 0) {
            this.executionPath = ExecutionPath.DIRECT;
            return this.executionPath;
        }
        if (registeredWorkers <= 1 || this.bodyCostHistoryCount < BODY_COST_MIN_HISTORY) {
            this.executionPath = ExecutionPath.DIRECT;
            return this.executionPath;
        }

        if (hasPlentifulProductiveHandles(productiveHandles, registeredWorkers)
                || (contention <= CONTENTION_THRESHOLD && this.smoothedBodyCostNs <= this.bodyCostDirectThreshold)) {
            this.executionPath = ExecutionPath.DIRECT;
            return ExecutionPath.DIRECT;
        }
        this.executionPath = ExecutionPath.STAGED;
        return ExecutionPath.STAGED;
    }

    private static boolean hasPlentifulProductiveHandles(long productiveHandles, int registeredWorkers) {
        return registeredWorkers > 0 && productiveHandles >= registeredWorkers;
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
        if (this.bodyCostHistoryCount < BODY_COST_WINDOW_SAMPLES) {
            this.bodyCostWindow[this.bodyCostHistoryCount] = (double) elapsedNs;
            this.bodyCostHistoryCount++;
            if (this.bodyCostHistoryCount == BODY_COST_WINDOW_SAMPLES) {
                updateBodyCostEstimate();
            }
            return;
        }

        this.bodyCostWindow[this.bodyCostWindowIndex] = (double) elapsedNs;
        this.bodyCostWindowIndex = (this.bodyCostWindowIndex + 1) & BODY_COST_WINDOW_MASK;
        if (this.bodyCostHistoryCount < Integer.MAX_VALUE) {
            this.bodyCostHistoryCount++;
        }
        if (this.bodyCostWindowIndex == 0) {
            updateBodyCostEstimate();
        }
    }

    /// Completes a productive batch and returns the next batch within `eligibleCap`.
    long completeBatch(long eligibleCap) {
        long cap = Math.max(2L, eligibleCap);
        long desired = Math.min(desiredBatchSize(), cap);
        this.batchSize = applyBatchSlew(this.batchSize, desired, cap);
        return this.batchSize;
    }

    private long desiredBatchSize() {
        if (this.serviceTimeNs <= 0.0) {
            return this.batchSize;
        }
        long workTarget =
                this.executionPath == ExecutionPath.DIRECT ? DIRECT_BATCH_WORK_TARGET_NS : STAGED_BATCH_WORK_TARGET_NS;
        long raw = (long) Math.floor(workTarget / Math.max(this.serviceTimeNs, 1.0));
        return Math.max(2L, Long.highestOneBit(Math.max(2L, raw)));
    }

    private static long applyBatchSlew(long current, long desired, long cap) {
        long minimum = (current >>> 1) + (current & 1L);
        long maximum = saturatingDouble(current);
        long next = Math.max(minimum, Math.min(desired, maximum));
        return Math.max(2L, Math.min(next, cap));
    }

    /// Restores the captured initial mode, batch two, and empty timing and hysteresis state.
    void reset() {
        this.executionPath = ExecutionPath.DIRECT;
        this.batchSize = 2L;
        this.serviceTimeNs = 0.0;
        this.smoothedBodyCostNs = 0.0;
        this.bodyCostHistoryCount = 0;
        this.bodyCostWindowIndex = 0;
        this.expensiveConfirmationWindows = 0;
    }

    /// Returns the current EWMA service estimate in nanoseconds per frame, or zero before sampling.
    double serviceTimeNs() {
        return this.serviceTimeNs;
    }

    /// Returns whether the body-cost estimator has completed its minimum history window.
    boolean hasBodyCostHistory() {
        return this.bodyCostHistoryCount >= BODY_COST_MIN_HISTORY;
    }

    /// Returns the configured default IDLE park duration.
    long idleParkNs() {
        return this.idlePolicy.idleParkNs();
    }

    long contentionHalfLifeNanos() {
        return this.idlePolicy.contentionHalfLifeNanos();
    }

    /// Returns the current sparse executor-body estimate in nanoseconds.
    double smoothedBodyCostNs() {
        return this.smoothedBodyCostNs;
    }

    /// Updates one non-overlapping second minimum and confirms expensive work across two windows.
    private void updateBodyCostEstimate() {
        applyBodyCostEstimate(secondMinimumBodyCost());
    }

    private double secondMinimumBodyCost() {
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
        return secondMinimum;
    }

    private void applyBodyCostEstimate(double candidate) {
        if (candidate >= this.expensiveBodyCostThreshold) {
            if (this.expensiveConfirmationWindows < EXPENSIVE_CONFIRMATION_WINDOWS) {
                this.expensiveConfirmationWindows++;
            }
            if (this.expensiveConfirmationWindows == EXPENSIVE_CONFIRMATION_WINDOWS) {
                this.smoothedBodyCostNs = candidate;
            }
            return;
        }
        this.expensiveConfirmationWindows = 0;
        this.smoothedBodyCostNs = candidate;
    }
}
