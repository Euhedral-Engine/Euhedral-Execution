package io.euhedral_execution.core.control_plane;

import static io.euhedral_execution.core.control_plane.FragmentControlConfig.DEFAULT_PARK_NS;

import io.euhedral_execution.core.config.FragmentDecisionWeights;
import io.euhedral_execution.core.config.FragmentDecisionWeights.IdlePolicy;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.BodyCostThresholds;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Owner-thread policy for choosing direct or staged execution and a bounded batch size.
///
/// All fields use plain access because one pinned fragment thread owns the policy for its lifetime.
final class FragmentDecisionTree {
    static final long CONTENTION_THRESHOLD = 850_000; // 85%

    static final long DIRECT_BATCH_WORK_TARGET_NS = 250_000L;
    static final long STAGED_BATCH_WORK_TARGET_NS = 8_000_000L;

    // Measurement Variables
    static final int BODY_COST_WINDOW_SAMPLES = 32;
    static final int BODY_COST_WINDOW_MASK = BODY_COST_WINDOW_SAMPLES - 1;
    static final int BODY_COST_MIN_HISTORY = 32;
    static final int EXPENSIVE_CONFIRMATION_WINDOWS = 2;
    static final int SPIN_MISSES = 64;

    private final int core;
    private final int socket;
    private final FragmentObserver observer;
    private final Integer forcedActiveParticipantCount;
    private final long cacheParkNs;
    private final boolean participationPolicyEnabled;

    private final BodyCostThresholds idleBodyCostThresholds;
    private final IdlePolicy idleTimeNs;
    private final long bodyCostDirectThreshold;

    private final long maxBodyCostThreshold;
    private final double[] bodyCostWindow = new double[BODY_COST_WINDOW_SAMPLES];
    private ExecutionPath executionPath;
    private long batchSize;
    private double serviceTimeNs;
    private double smoothedBodyCostNs;
    private int bodyCostHistoryCount;
    private int bodyCostWindowIndex;
    private int expensiveConfirmationWindows;
    private int activeMissStreak;

    FragmentDecisionTree(
            @NonNull FragmentDecisionWeights decisionWeights,
            @Nullable FragmentObserver observer,
            int core,
            int socket) {
        this(decisionWeights, observer, core, socket, null, FragmentControlConfig.DEFAULT_CACHE_PARK_NS, true);
    }

    FragmentDecisionTree(
            @NonNull FragmentDecisionWeights decisionWeights,
            @Nullable FragmentObserver observer,
            int core,
            int socket,
            @Nullable Integer forcedActiveParticipantCount,
            long cacheParkNs) {
        this(decisionWeights, observer, core, socket, forcedActiveParticipantCount, cacheParkNs, true);
    }

    FragmentDecisionTree(
            @NonNull FragmentDecisionWeights decisionWeights,
            @Nullable FragmentObserver observer,
            int core,
            int socket,
            @Nullable Integer forcedActiveParticipantCount,
            long cacheParkNs,
            boolean participationPolicyEnabled) {
        Objects.requireNonNull(decisionWeights);
        if (forcedActiveParticipantCount != null && forcedActiveParticipantCount <= 0) {
            throw new IllegalArgumentException("forcedActiveParticipantCount must be positive");
        }
        if (cacheParkNs < 0L) {
            throw new IllegalArgumentException("cacheParkNs must not be negative");
        }
        this.observer = observer;
        this.core = core;
        this.socket = socket;
        this.forcedActiveParticipantCount = forcedActiveParticipantCount;
        this.cacheParkNs = cacheParkNs;
        this.participationPolicyEnabled = participationPolicyEnabled;

        FragmentControlConfig config = new FragmentControlConfig(decisionWeights);
        this.idleBodyCostThresholds = config.idleBodyCostThresholds;
        this.idleTimeNs = config.idleTimeNs;
        this.maxBodyCostThreshold = this.idleBodyCostThresholds.h;
        this.bodyCostDirectThreshold = config.bodyCostDirectThreshold;
        reset();
    }

    /// Doubles a positive batch limit without signed overflow.
    static long saturatingDouble(long value) {
        return value > Long.MAX_VALUE / 2L ? Long.MAX_VALUE : value * 2L;
    }

    /// Makes an idling decision using the idle branch and parks the fragment
    public long idle(
            long cycleEpoch,
            long batchEpoch,
            long upstreamHandles,
            int registeredWorkers,
            int workerRank,
            long contention) {
        if (upstreamHandles <= 0) {
            LockSupport.parkNanos(DEFAULT_PARK_NS);
            return DEFAULT_PARK_NS;
        }
        if (registeredWorkers <= 1 || bodyCostHistoryCount < BODY_COST_MIN_HISTORY) {
            return -1L;
        }
        if (workerRank <= 0) {
            return -1L;
        }

        if (contention <= CONTENTION_THRESHOLD) {
            if (this.observer != null) {
                this.observer.idleBranchDecision(
                        this.core, this.socket, cycleEpoch, batchEpoch, 0, -1, contention, this.smoothedBodyCostNs);
            }
            return -1;
        }

        return idle(cycleEpoch, batchEpoch, this.idleBodyCostThresholds, this.idleTimeNs, contention);
    }

    private long idle(
            long cycleEpoch, long batchEpoch, BodyCostThresholds thresholds, IdlePolicy policy, long contention) {
        int decision = -1;
        long idleDurationNs;
        try {
            if (this.smoothedBodyCostNs <= thresholds.xs) {
                decision = 0;
                idleDurationNs = policy.xsPark();
                LockSupport.parkNanos(idleDurationNs);
                return idleDurationNs;
            }
            if (this.smoothedBodyCostNs <= thresholds.s) {
                decision = 1;
                idleDurationNs = policy.sPark();
                LockSupport.parkNanos(idleDurationNs);
                return idleDurationNs;
            }
            if (this.smoothedBodyCostNs <= thresholds.m) {
                decision = 2;
                idleDurationNs = policy.mPark();
                LockSupport.parkNanos(idleDurationNs);
                return idleDurationNs;
            }
            if (this.smoothedBodyCostNs <= thresholds.h) {
                decision = 3;
                idleDurationNs = policy.hPark();
                LockSupport.parkNanos(idleDurationNs);
                return idleDurationNs;
            }
            decision = 4;
            idleDurationNs = policy.xhPark();
            LockSupport.parkNanos(idleDurationNs);
            return idleDurationNs;
        } finally {
            if (this.observer != null) {
                this.observer.idleBranchDecision(
                        this.core,
                        this.socket,
                        cycleEpoch,
                        batchEpoch,
                        1,
                        decision,
                        contention,
                        this.smoothedBodyCostNs);
            }
        }
    }

    ExecutionPath executionPath(
            long cycleEpoch,
            long batchEpoch,
            long productiveHandles,
            long upstreamHandles,
            int registeredWorkers,
            long contention,
            int workerRank) {
        if (isForcedCacheRank(workerRank)) {
            recordExecDecision(cycleEpoch, batchEpoch, 0, 0, contention);
            this.executionPath = ExecutionPath.CACHE;
            return this.executionPath;
        }
        if (upstreamHandles <= 0) {
            this.executionPath = ExecutionPath.SKIP_THEN_DIRECT;
            return this.executionPath;
        }
        if (registeredWorkers <= 1 || this.bodyCostHistoryCount < BODY_COST_MIN_HISTORY) {
            this.executionPath = ExecutionPath.DIRECT;
            return this.executionPath;
        }

        if (this.executionPath == ExecutionPath.SKIP_THEN_DIRECT) {
            this.executionPath = ExecutionPath.DIRECT;
            return this.executionPath;
        }

        if (this.forcedActiveParticipantCount == null
                && shouldCacheExecute(
                        (double) contention / 1_000_000.0, productiveHandles, registeredWorkers, workerRank)) {
            recordExecDecision(cycleEpoch, batchEpoch, 0, 0, contention);
            this.executionPath = ExecutionPath.CACHE;
            return ExecutionPath.CACHE;
        }

        if (contention <= CONTENTION_THRESHOLD && this.smoothedBodyCostNs <= this.bodyCostDirectThreshold) {
            recordExecDecision(cycleEpoch, batchEpoch, 0, 0, contention);
            this.executionPath = ExecutionPath.DIRECT;
            return ExecutionPath.DIRECT;
        }
        recordExecDecision(cycleEpoch, batchEpoch, 1, 0, contention);
        this.executionPath = ExecutionPath.STAGED;
        return ExecutionPath.STAGED;
    }

    /// Returns whether the current inputs would select CACHE without mutating decision state or telemetry.
    boolean willCacheExecute(
            long productiveHandles, long upstreamHandles, int registeredWorkers, long contention, int workerRank) {
        if (isForcedCacheRank(workerRank)) {
            return true;
        }
        if (this.forcedActiveParticipantCount != null
                || upstreamHandles <= 0
                || registeredWorkers <= 1
                || this.bodyCostHistoryCount < BODY_COST_MIN_HISTORY
                || this.executionPath == ExecutionPath.SKIP_THEN_DIRECT) {
            return false;
        }
        return shouldCacheExecute((double) contention / 1_000_000.0, productiveHandles, registeredWorkers, workerRank);
    }

    private boolean isForcedCacheRank(int workerRank) {
        if (this.forcedActiveParticipantCount == null || workerRank <= 0) {
            return false;
        }
        return workerRank > this.forcedActiveParticipantCount;
    }

    boolean shouldCacheExecute(double contention, long productiveHandles, int registeredWorkers, int workerRank) {
        if (workerRank <= 1 || registeredWorkers <= 1) {
            return false;
        }
        if (productiveHandles <= 0) {
            return true;
        }
        if (!this.participationPolicyEnabled) {
            return false;
        }
        return ParticipationLogisticModel.shouldCache(
                workerRank, productiveHandles, registeredWorkers, this.smoothedBodyCostNs, contention);
    }

    private void recordExecDecision(
            long cycleEpoch, long batchEpoch, int contentionPolicy, int bodyPolicy, long contention) {
        if (this.observer != null) {
            this.observer.execBranchDecision(
                    this.core,
                    this.socket,
                    cycleEpoch,
                    batchEpoch,
                    contentionPolicy,
                    bodyPolicy,
                    contention,
                    this.smoothedBodyCostNs);
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

    /// Returns whether the body-cost estimator has completed its minimum history window.
    boolean hasBodyCostHistory() {
        return this.bodyCostHistoryCount >= BODY_COST_MIN_HISTORY;
    }

    /// Returns the configured CACHE miss park duration for this actuator fixture.
    long cacheParkNs() {
        return this.cacheParkNs;
    }

    /// Returns the current sparse executor-body estimate in nanoseconds.
    double smoothedBodyCostNs() {
        return this.smoothedBodyCostNs;
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
