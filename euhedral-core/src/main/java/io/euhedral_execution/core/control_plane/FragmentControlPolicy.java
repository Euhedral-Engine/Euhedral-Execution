package io.euhedral_execution.core.control_plane;

import io.euhedral_execution.core.utils.MicroCalibrator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;
import org.jspecify.annotations.NonNull;

/// Owner-thread policy for choosing direct or staged execution and a bounded batch size.
///
/// All fields use plain access because one pinned fragment thread owns the policy for its lifetime.
public final class FragmentControlPolicy {

    static final String IDLE_CONTENTION_THRESHOLDS = "euhedral.fragment.idle.contentionThresholds";
    static final String IDLE_BODY_COST_WEIGHTS = "euhedral.fragment.idle.bodyCostThresholds";
    static final String IDLE_POLICY_PARK_NS = "euhedral.fragment.idle.parkTimeValuesNs";
    static final String EXEC_CONTENTION_THRESHOLDS = "euhedral.fragment.execution.contentionThresholds";
    static final String EXEC_BODY_COST_WEIGHTS = "euhedral.fragment.execution.bodyCostWeights";
    static final String EXEC_CONTENTION_POLICY = "euhedral.fragment.execution.contentionPolicy";

    static final long DIRECT_BATCH_WORK_TARGET_NS = 250_000L;
    static final long STAGED_BATCH_WORK_TARGET_NS = 8_000_000L;

    static final long DEFAULT_PARK_NS = 15_000L;

    // Contention Thresholds
    // Calibration-host candidate: Phase 14 left a gap between 582k DIRECT and 705k high contention.
    static final long DEFAULT_LOW_CONTENTION_THRESHOLD = 650_000L; // 65%
    static final long DEFAULT_HIGH_CONTENTION_THRESHOLD = 980_000L; // 98%

    // Measurement Variables
    static final int BODY_COST_WINDOW_SAMPLES = 32;
    static final int BODY_COST_MIN_HISTORY = 32;
    static final int EXPENSIVE_CONFIRMATION_WINDOWS = 2;
    static final int SPIN_MISSES = 64;

    final ContentionThresholds idleContentionThresholds;
    final List<BodyCostThresholds> idleBodyCostThresholds;
    final List<IdlePolicy> idleTimeNs;

    final ContentionThresholds execContentionThresholds;
    final List<BodyCostThresholds> execBodyCostThresholds;
    final List<ExecutionPolicy> executionPolicies;

    final long maxBodyCostThreshold;

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
    public FragmentControlPolicy() {
        this.idleContentionThresholds = ContentionThresholds.IDLE_DEFAULTS;
        this.idleBodyCostThresholds = BodyCostThresholds.IDLE_DEFAULTS;
        this.idleTimeNs = IdlePolicy.DEFAULT;
        this.execContentionThresholds = ContentionThresholds.EXEC_DEFAULTS;
        this.execBodyCostThresholds = BodyCostThresholds.EXEC_DEFAULTS;
        this.executionPolicies = ExecutionPolicy.DEFAULT;

        long max = 0;
        for (int i = 0; i < this.execBodyCostThresholds.size(); i++) {
            max = Math.max(max, Math.max(this.idleBodyCostThresholds.get(i).h, this.execBodyCostThresholds.get(i).h));
        }
        this.maxBodyCostThreshold = max;
        reset();
    }

    FragmentControlPolicy(
            ContentionThresholds idleContentionThresholds,
            BodyCostWeights[] idleBodyCostWeights,
            IdlePolicy[] idleTimeNs,
            ContentionThresholds execContentionThresholds,
            BodyCostWeights[] execBodyCostWeights,
            ExecutionPolicy[] executionPolicies) {
        Objects.requireNonNull(idleContentionThresholds);
        Objects.requireNonNull(idleBodyCostWeights);
        Objects.requireNonNull(idleTimeNs);
        Objects.requireNonNull(execContentionThresholds);
        Objects.requireNonNull(execBodyCostWeights);
        Objects.requireNonNull(executionPolicies);
        if (idleBodyCostWeights.length != 5) {
            throw new IllegalArgumentException("Length of idleBodyCostWeights must be 5");
        }
        if (execBodyCostWeights.length != 5) {
            throw new IllegalArgumentException("Length of execBodyCostWeights must be 5");
        }

        this.idleContentionThresholds = idleContentionThresholds;
        this.execContentionThresholds = execContentionThresholds;
        this.executionPolicies = List.of(executionPolicies);
        this.idleTimeNs = List.of(idleTimeNs);

        BodyCostThresholds[] idle = new BodyCostThresholds[5];
        BodyCostThresholds[] exec = new BodyCostThresholds[5];

        MicroCalibrator calibrator = new MicroCalibrator();
        calibrator.warmup();
        long max = 0;
        for (int i = 0; i < idleBodyCostWeights.length; i++) {
            idle[i] = new BodyCostThresholds(calibrator, idleBodyCostWeights[i]);
            exec[i] = new BodyCostThresholds(calibrator, execBodyCostWeights[i]);
            max = Math.max(max, Math.max(idle[i].h, exec[i].h));
        }
        this.idleBodyCostThresholds = List.of(idle);
        this.execBodyCostThresholds = List.of(exec);
        this.maxBodyCostThreshold = max;
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
        if (contention <= thresholds.xs) {
            idle(idleBodyCost.getFirst(), idleTimeNs.getFirst());
            return;
        }
        if (contention <= thresholds.s) {
            idle(idleBodyCost.get(1), idleTimeNs.get(1));
            return;
        }
        if (contention <= thresholds.m) {
            idle(idleBodyCost.get(2), idleTimeNs.get(2));
            return;
        }
        if (contention <= thresholds.h) {
            idle(idleBodyCost.get(3), idleTimeNs.get(3));
            return;
        }
        idle(idleBodyCost.getLast(), idleTimeNs.getLast());
    }

    private void idle(BodyCostThresholds thresholds, IdlePolicy policy) {
        if (this.smoothedBodyCostNs <= thresholds.xs) {
            LockSupport.parkNanos(policy.xsPark);
            return;
        }
        if (this.smoothedBodyCostNs <= thresholds.s) {
            LockSupport.parkNanos(policy.sPark);
            return;
        }
        if (this.smoothedBodyCostNs <= thresholds.m) {
            LockSupport.parkNanos(policy.mPark);
            return;
        }
        if (this.smoothedBodyCostNs <= thresholds.h) {
            LockSupport.parkNanos(policy.hPark);
            return;
        }
        LockSupport.parkNanos(policy.xhPark);
    }

    ExecutionPath selectExecutionPath(long upstreamHandles, long registeredWorkers, long contention) {
        if (upstreamHandles <= 0) {
            this.executionPath = ExecutionPath.SKIP;
            return this.executionPath;
        }
        if (registeredWorkers <= 1 || this.bodyCostHistoryCount < BODY_COST_MIN_HISTORY) {
            this.executionPath = ExecutionPath.DIRECT;
            return this.executionPath;
        }

        this.executionPath = selectExecutionPath(
                this.execContentionThresholds, this.execBodyCostThresholds, this.executionPolicies, contention);
        return this.executionPath;
    }

    private ExecutionPath selectExecutionPath(
            ContentionThresholds thresholds,
            List<BodyCostThresholds> execBodyCost,
            List<ExecutionPolicy> policies,
            long contention) {
        if (contention <= thresholds.xs) {
            return selectExecutionPath(execBodyCost.getFirst(), policies.getFirst());
        }
        if (contention <= thresholds.s) {
            return selectExecutionPath(execBodyCost.get(1), policies.get(1));
        }
        if (contention <= thresholds.m) {
            return selectExecutionPath(execBodyCost.get(2), policies.get(2));
        }
        if (contention <= thresholds.h) {
            return selectExecutionPath(execBodyCost.get(3), policies.get(3));
        }
        return selectExecutionPath(execBodyCost.getLast(), policies.getLast());
    }

    private ExecutionPath selectExecutionPath(BodyCostThresholds thresholds, ExecutionPolicy policy) {
        if (this.smoothedBodyCostNs <= thresholds.xs) {
            return policy.xsContention;
        }
        if (this.smoothedBodyCostNs <= thresholds.s) {
            return policy.sContention;
        }
        if (this.smoothedBodyCostNs <= thresholds.m) {
            return policy.mContention;
        }
        if (this.smoothedBodyCostNs <= thresholds.h) {
            return policy.hContention;
        }
        return policy.xhContention;
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

    /// Clears the active miss streak after any productive execution cycle.
    void recordProgress() {
        this.activeMissStreak = 0;
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

    /// Execution strategies selected only at completed-batch boundaries.
    public enum ExecutionPath {
        DIRECT,
        STAGED,
        SKIP
    }

    public record ContentionThresholds(long xs, long s, long m, long h, long xh) {
        public static final ContentionThresholds IDLE_DEFAULTS;
        public static final ContentionThresholds EXEC_DEFAULTS;

        static {
            String prop = System.getProperty(IDLE_CONTENTION_THRESHOLDS);
            if (prop != null) {
                IDLE_DEFAULTS = readThresholds(IDLE_CONTENTION_THRESHOLDS);
            } else {
                IDLE_DEFAULTS = new ContentionThresholds(
                        DEFAULT_LOW_CONTENTION_THRESHOLD,
                        DEFAULT_LOW_CONTENTION_THRESHOLD,
                        DEFAULT_LOW_CONTENTION_THRESHOLD,
                        DEFAULT_LOW_CONTENTION_THRESHOLD,
                        DEFAULT_HIGH_CONTENTION_THRESHOLD);
            }

            prop = System.getProperty(EXEC_CONTENTION_THRESHOLDS);
            if (prop != null) {
                EXEC_DEFAULTS = readThresholds(EXEC_CONTENTION_THRESHOLDS);
            } else {
                EXEC_DEFAULTS = new ContentionThresholds(
                        DEFAULT_LOW_CONTENTION_THRESHOLD,
                        DEFAULT_LOW_CONTENTION_THRESHOLD,
                        DEFAULT_LOW_CONTENTION_THRESHOLD,
                        DEFAULT_LOW_CONTENTION_THRESHOLD,
                        DEFAULT_HIGH_CONTENTION_THRESHOLD);
            }
        }

        public ContentionThresholds {
            if (xh > 1_000_000L || xs > s || s > m || m > h || h > xh) {
                throw new IllegalArgumentException(
                        "This class's parameters must have values from [0..1,000,000] in increasing order");
            }
        }

        private static ContentionThresholds readThresholds(String property) {
            String raw = System.getProperty(property);
            String[] tokens = raw.split(",");
            if (tokens.length != 5) {
                throw new IllegalArgumentException(property
                        + " must have 5 comma-separated integers with values [0..1,000,000] in increasing order");
            }
            long xs = Long.parseLong(tokens[0]);
            long s = Long.parseLong(tokens[1]);
            long m = Long.parseLong(tokens[2]);
            long h = Long.parseLong(tokens[3]);
            long xh = Long.parseLong(tokens[4]);

            if (xs > s || s > m || m > h || h > xh) {
                throw new IllegalArgumentException(property
                        + " must have 5 comma-separated integers with values [0..1,000,000] in increasing order");
            }
            return new ContentionThresholds(xs, s, m, h, xh);
        }
    }

    public static final class BodyCostThresholds {
        public static final List<BodyCostThresholds> IDLE_DEFAULTS;
        public static final List<BodyCostThresholds> EXEC_DEFAULTS;

        static {
            BodyCostThresholds[] idle = new BodyCostThresholds[5];
            BodyCostThresholds[] exec = new BodyCostThresholds[5];

            MicroCalibrator calibrator = new MicroCalibrator();
            calibrator.warmup();

            for (int i = 0; i < idle.length; i++) {
                idle[i] = new BodyCostThresholds(calibrator, BodyCostWeights.IDLE_DEFAULTS.get(i));
                exec[i] = new BodyCostThresholds(calibrator, BodyCostWeights.EXEC_DEFAULTS.get(i));
            }

            IDLE_DEFAULTS = List.of(idle);
            EXEC_DEFAULTS = List.of(exec);
        }

        public final long xs;
        public final long s;
        public final long m;
        public final long h;

        public BodyCostThresholds(long xs, long s, long m, long h) {
            if (xs > s || s > m || m > h) {
                throw new IllegalArgumentException("This class's parameters must be in increasing order");
            }
            this.xs = xs;
            this.s = s;
            this.m = m;
            this.h = h;
        }

        public BodyCostThresholds(@NonNull MicroCalibrator calibrator, @NonNull BodyCostWeights weights) {
            Objects.requireNonNull(calibrator);
            Objects.requireNonNull(weights);
            if (weights.xs > weights.s || weights.s > weights.m || weights.m > weights.h) {
                throw new IllegalArgumentException("Weights must be in increasing order");
            }
            this.xs = calibrator.benchmark(weights.xs);
            if (weights.xs == weights.s) {
                this.s = this.xs;
            } else {
                this.s = calibrator.benchmark(weights.s);
            }
            if (weights.s == weights.m) {
                this.m = this.s;
            } else {
                this.m = calibrator.benchmark(weights.m);
            }
            if (weights.m == weights.h) {
                this.h = this.m;
            } else {
                this.h = calibrator.benchmark(weights.h);
            }
        }
    }

    public record BodyCostWeights(int xs, int s, int m, int h) {
        public static final List<BodyCostWeights> IDLE_DEFAULTS;
        public static final List<BodyCostWeights> EXEC_DEFAULTS;

        static {
            String prop = System.getProperty(IDLE_BODY_COST_WEIGHTS);
            if (prop != null) {
                IDLE_DEFAULTS = readWeights(IDLE_BODY_COST_WEIGHTS, prop);
            } else {
                IDLE_DEFAULTS = List.of(
                        new BodyCostWeights(0, 0, 24, 256), // XS Contention
                        new BodyCostWeights(0, 0, 24, 256), // S Contention
                        new BodyCostWeights(0, 0, 24, 256), // M Contention
                        new BodyCostWeights(0, 24, 96, 256), // H Contention
                        new BodyCostWeights(0, 24, 96, 256)); // XH Contention
            }

            prop = System.getProperty(EXEC_BODY_COST_WEIGHTS);
            if (prop != null) {
                EXEC_DEFAULTS = readWeights(EXEC_BODY_COST_WEIGHTS, prop);
            } else {
                EXEC_DEFAULTS = List.of(
                        new BodyCostWeights(0, 24, 96, 256), // XS Contention
                        new BodyCostWeights(0, 24, 96, 256), // S Contention
                        new BodyCostWeights(0, 24, 96, 256), // M Contention
                        new BodyCostWeights(0, 24, 96, 256), // H Contention
                        new BodyCostWeights(0, 24, 96, 256)); // XH Contention
            }
        }

        private static List<BodyCostWeights> readWeights(String property, String raw) {
            String[] sets = raw.split(",");
            if (sets.length != 5) {
                throw new IllegalArgumentException(property
                        + " must have 5 sets of 4 comma-separated 32-bit integers with values in increasing"
                        + " order within each set. Format the list like: property=\"[],[],[],[]\"");
            }

            BodyCostWeights[] thresholds = new BodyCostWeights[sets.length];
            for (int i = 0; i < sets.length; i++) {
                String[] tokens = sets[i].replace("[", "").replace("]", "").split(",");
                if (tokens.length != 4) {
                    throw new IllegalArgumentException("Set " + i + " does not have 4 32-bit integers");
                }

                int xs = Integer.parseInt(tokens[0]);
                int s = Integer.parseInt(tokens[1]);
                int m = Integer.parseInt(tokens[2]);
                int h = Integer.parseInt(tokens[3]);

                if (xs > s || s > m || m > h) {
                    throw new IllegalArgumentException(property
                            + " must have 5 sets of 4 comma-separated 32-bit integers with values in increasing"
                            + " order within each set. Format the list like: property=\"[],[],[],[]\"");
                }
                thresholds[i] = new BodyCostWeights(xs, s, m, h);
            }
            return List.of(thresholds);
        }
    }

    public record IdlePolicy(long xsPark, long sPark, long mPark, long hPark, long xhPark) {
        public static final List<IdlePolicy> DEFAULT;

        static {
            String prop = System.getProperty(IDLE_POLICY_PARK_NS);
            if (prop != null) {
                DEFAULT = readPolicy(prop);
            } else {
                DEFAULT = List.of(
                        new IdlePolicy(0, 0, 0, 0, 0), // XS Contention
                        new IdlePolicy(0, 0, 0, 0, 0), // S Contention
                        new IdlePolicy(0, 0, 0, 0, 0), // M Contention
                        new IdlePolicy(0, 0, 0, 0, 0), // H Contention
                        new IdlePolicy(DEFAULT_PARK_NS, DEFAULT_PARK_NS, 0, 0, 0) // XH Contention
                        );
            }
        }

        private static List<IdlePolicy> readPolicy(String raw) {
            String[] sets = raw.split(",");
            if (sets.length != 5) {
                throw new IllegalArgumentException(FragmentControlPolicy.IDLE_POLICY_PARK_NS
                        + " must have 5 sets of 5 comma-separated 64-bit integers within each set. Formatting:"
                        + " propertyName=\"[],[],[],[]\"");
            }

            IdlePolicy[] policies = new IdlePolicy[sets.length];
            for (int i = 0; i < policies.length; i++) {
                String[] tokens = sets[i].split(",");
                if (tokens.length != 5) {
                    throw new IllegalArgumentException(FragmentControlPolicy.IDLE_POLICY_PARK_NS
                            + " must have 5 sets of 5 comma-separated 64-bit integers within each set."
                            + " Formatting: propertyName=\"[],[],[],[]\"");
                }
                long xs = Long.parseLong(tokens[0]);
                long s = Long.parseLong(tokens[1]);
                long m = Long.parseLong(tokens[2]);
                long h = Long.parseLong(tokens[3]);
                long xh = Long.parseLong(tokens[4]);

                if (xs > s || s > m || m > h || h > xh) {
                    throw new IllegalArgumentException(FragmentControlPolicy.IDLE_POLICY_PARK_NS
                            + " must have 5 sets of 5 comma-separated 64-bit integers within each set."
                            + " Formatting: propertyName=\"[],[],[],[]\"");
                }
                policies[i] = new IdlePolicy(xs, s, m, h, xh);
            }
            return List.of(policies);
        }
    }

    public record ExecutionPolicy(
            ExecutionPath xsContention,
            ExecutionPath sContention,
            ExecutionPath mContention,
            ExecutionPath hContention,
            ExecutionPath xhContention) {
        public static final List<ExecutionPolicy> DEFAULT;

        static {
            String prop = System.getProperty(EXEC_CONTENTION_POLICY);
            if (prop != null) {
                DEFAULT = readPolicy(prop);
            } else {
                DEFAULT = List.of(
                        new ExecutionPolicy(
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.STAGED),
                        new ExecutionPolicy(
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.STAGED),
                        new ExecutionPolicy(
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.STAGED),
                        new ExecutionPolicy(
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.STAGED),
                        new ExecutionPolicy(
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.STAGED));
            }
        }

        private static List<ExecutionPolicy> readPolicy(String raw) {
            String[] sets = raw.split(",");
            if (sets.length != 5) {
                throw new IllegalArgumentException(FragmentControlPolicy.EXEC_CONTENTION_POLICY
                        + " must have 5 sets of 5 comma-separated enums (DIRECT, STAGED, SKIP) within each set."
                        + " Formatting: propertyName=\"[],[],[],[]\"");
            }

            ExecutionPolicy[] policies = new ExecutionPolicy[sets.length];
            for (int i = 0; i < policies.length; i++) {
                String[] tokens = sets[i].split(",");
                if (tokens.length != 5) {
                    throw new IllegalArgumentException(FragmentControlPolicy.EXEC_CONTENTION_POLICY
                            + " must have 5 sets of 5 comma-separated enums (DIRECT, STAGED, SKIP) within each set."
                            + " Formatting: propertyName=\"[],[],[],[]\"");
                }
                ExecutionPath xs = ExecutionPath.valueOf(tokens[0].toUpperCase());
                ExecutionPath s = ExecutionPath.valueOf(tokens[1].toUpperCase());
                ExecutionPath m = ExecutionPath.valueOf(tokens[2].toUpperCase());
                ExecutionPath h = ExecutionPath.valueOf(tokens[3].toUpperCase());
                ExecutionPath xh = ExecutionPath.valueOf(tokens[4].toUpperCase());

                policies[i] = new ExecutionPolicy(xs, s, m, h, xh);
            }
            return List.of(policies);
        }
    }
}
