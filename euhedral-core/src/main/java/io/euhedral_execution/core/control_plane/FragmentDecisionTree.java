package io.euhedral_execution.core.control_plane;

import static io.euhedral_execution.core.control_plane.FragmentControlConfig.DEFAULT_PARK_NS;

import io.euhedral_execution.core.control_plane.FragmentControlConfig.BodyCostThresholds;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.BodyCostWeights;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ContentionThresholds;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPolicy;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.IdlePolicy;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.NonNull;

/// Owner-thread policy for choosing direct or staged execution and a bounded batch size.
///
/// All fields use plain access because one pinned fragment thread owns the policy for its lifetime.
final class FragmentDecisionTree {

    static final long DIRECT_BATCH_WORK_TARGET_NS = 250_000L;
    static final long STAGED_BATCH_WORK_TARGET_NS = 8_000_000L;

    // Measurement Variables
    static final int BODY_COST_WINDOW_SAMPLES = 32;
    static final int BODY_COST_MIN_HISTORY = 32;
    static final int EXPENSIVE_CONFIRMATION_WINDOWS = 2;
    static final int SPIN_MISSES = 64;

    private final ContentionThresholds idleContentionThresholds;
    private final List<BodyCostThresholds> idleBodyCostThresholds;
    private final List<IdlePolicy> idleTimeNs;

    private final ContentionThresholds execContentionThresholds;
    private final List<BodyCostThresholds> execBodyCostThresholds;
    private final List<ExecutionPolicy> executionPolicies;

    private final long maxBodyCostThreshold;

    private ExecutionPath executionPath;
    private long batchSize;
    private double serviceTimeNs;
    private final double[] bodyCostWindow = new double[BODY_COST_WINDOW_SAMPLES];
    private double smoothedBodyCostNs;
    private int bodyCostHistoryCount;
    private int bodyCostWindowIndex;
    private int expensiveConfirmationWindows;
    private int activeMissStreak;

    /// Creates a policy, capturing any setup-only diagnostic override before owner-thread use.
    FragmentDecisionTree() {
        this(new FragmentControlConfig(
                ContentionThresholds.IDLE_DEFAULTS,
                BodyCostWeights.IDLE_DEFAULTS,
                IdlePolicy.DEFAULT,
                ContentionThresholds.EXEC_DEFAULTS,
                BodyCostWeights.EXEC_DEFAULTS,
                ExecutionPolicy.DEFAULT));
    }

    FragmentDecisionTree(@NonNull FragmentControlConfig config) {
        Objects.requireNonNull(config);
        this.idleContentionThresholds = config.idleContentionThresholds;
        this.idleBodyCostThresholds = config.idleBodyCostThresholds;
        this.idleTimeNs = config.idleTimeNs;
        this.execContentionThresholds = config.execContentionThresholds;
        this.execBodyCostThresholds = config.execBodyCostThresholds;
        this.executionPolicies = config.executionPolicies;
        this.maxBodyCostThreshold = config.maxBodyCostThreshold;
        reset();
    }

    public void idle(long upstreamHandles, int registeredWorkers, int workerRank, long contention) {
        if (upstreamHandles <= 0) {
            LockSupport.parkNanos(DEFAULT_PARK_NS);
            return;
        }
        if (registeredWorkers <= 1 || bodyCostHistoryCount < BODY_COST_MIN_HISTORY) {
            return;
        }
        if (workerRank <= 0) {
            return;
        }

        idle(this.idleContentionThresholds, this.idleBodyCostThresholds, this.idleTimeNs, contention);
    }

    private void idle(
            ContentionThresholds thresholds,
            List<BodyCostThresholds> idleBodyCost,
            List<IdlePolicy> idleTimeNs,
            long contention) {
        if (contention <= thresholds.xs()) {
            idle(idleBodyCost.getFirst(), idleTimeNs.getFirst());
            return;
        }
        if (contention <= thresholds.s()) {
            idle(idleBodyCost.get(1), idleTimeNs.get(1));
            return;
        }
        if (contention <= thresholds.m()) {
            idle(idleBodyCost.get(2), idleTimeNs.get(2));
            return;
        }
        if (contention <= thresholds.h()) {
            idle(idleBodyCost.get(3), idleTimeNs.get(3));
            return;
        }
        idle(idleBodyCost.getLast(), idleTimeNs.getLast());
    }

    private void idle(BodyCostThresholds thresholds, IdlePolicy policy) {
        if (this.smoothedBodyCostNs <= thresholds.xs) {
            LockSupport.parkNanos(policy.xsPark());
            return;
        }
        if (this.smoothedBodyCostNs <= thresholds.s) {
            LockSupport.parkNanos(policy.sPark());
            return;
        }
        if (this.smoothedBodyCostNs <= thresholds.m) {
            LockSupport.parkNanos(policy.mPark());
            return;
        }
        if (this.smoothedBodyCostNs <= thresholds.h) {
            LockSupport.parkNanos(policy.hPark());
            return;
        }
        LockSupport.parkNanos(policy.xhPark());
    }

    ExecutionPath executionPath(long upstreamHandles, long registeredWorkers, long contention) {
        if (upstreamHandles <= 0) {
            this.executionPath = ExecutionPath.SKIP;
            return this.executionPath;
        }
        if (registeredWorkers <= 1 || this.bodyCostHistoryCount < BODY_COST_MIN_HISTORY) {
            this.executionPath = ExecutionPath.DIRECT;
            return this.executionPath;
        }

        this.executionPath = executionPath(
                this.execContentionThresholds, this.execBodyCostThresholds, this.executionPolicies, contention);
        return this.executionPath;
    }

    private ExecutionPath executionPath(
            ContentionThresholds thresholds,
            List<BodyCostThresholds> execBodyCost,
            List<ExecutionPolicy> policies,
            long contention) {
        if (contention <= thresholds.xs()) {
            return executionPath(execBodyCost.getFirst(), policies.getFirst());
        }
        if (contention <= thresholds.s()) {
            return executionPath(execBodyCost.get(1), policies.get(1));
        }
        if (contention <= thresholds.m()) {
            return executionPath(execBodyCost.get(2), policies.get(2));
        }
        if (contention <= thresholds.h()) {
            return executionPath(execBodyCost.get(3), policies.get(3));
        }
        return executionPath(execBodyCost.getLast(), policies.getLast());
    }

    private ExecutionPath executionPath(BodyCostThresholds thresholds, ExecutionPolicy policy) {
        if (this.smoothedBodyCostNs <= thresholds.xs) {
            return policy.xsContention();
        }
        if (this.smoothedBodyCostNs <= thresholds.s) {
            return policy.sContention();
        }
        if (this.smoothedBodyCostNs <= thresholds.m) {
            return policy.mContention();
        }
        if (this.smoothedBodyCostNs <= thresholds.h) {
            return policy.hContention();
        }
        return policy.xhContention();
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
        this.bodyCostWindowIndex = (this.bodyCostWindowIndex + 1) % BODY_COST_WINDOW_SAMPLES;
        if (this.bodyCostHistoryCount < Integer.MAX_VALUE) {
            this.bodyCostHistoryCount++;
        }
        if (this.bodyCostWindowIndex == 0) {
            updateBodyCostEstimate();
        }
    }

    /// Clears the active miss streak after any productive execution cycle.
    void recordProgress() {
        this.activeMissStreak = 0;
    }

    /// Completes a productive batch and returns the next batch within `eligibleCap`.
    long completeBatch(long eligibleCap) {
        long cap = Math.max(2L, eligibleCap);
        long desired = this.batchSize;
        if (this.serviceTimeNs > 0.0) {
            long workTarget = this.executionPath == ExecutionPath.DIRECT
                    ? DIRECT_BATCH_WORK_TARGET_NS
                    : STAGED_BATCH_WORK_TARGET_NS;
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

    /// Restores the captured initial mode, batch two, and empty timing and hysteresis state.
    void reset() {
        this.executionPath = ExecutionPath.DIRECT;
        this.batchSize = 2L;
        this.serviceTimeNs = 0.0;
        this.smoothedBodyCostNs = 0.0;
        this.bodyCostHistoryCount = 0;
        this.bodyCostWindowIndex = 0;
        this.expensiveConfirmationWindows = 0;
        this.activeMissStreak = 0;
    }

    /// Returns the current EWMA service estimate in nanoseconds per frame, or zero before sampling.
    double serviceTimeNs() {
        return this.serviceTimeNs;
    }

    /// Doubles a positive batch limit without signed overflow.
    static long saturatingDouble(long value) {
        return value > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : value * 2L;
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
        if (secondMinimum >= this.maxBodyCostThreshold) {
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
}
