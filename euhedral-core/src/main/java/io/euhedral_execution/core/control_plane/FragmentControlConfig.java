package io.euhedral_execution.core.control_plane;

import io.euhedral_execution.core.utils.MicroCalibrator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public final class FragmentControlConfig {
    public static final String IDLE_CONTENTION_THRESHOLDS = "euhedral.fragment.idle.contentionThresholds";
    public static final String IDLE_BODY_COST_WEIGHTS = "euhedral.fragment.idle.bodyCostThresholds";
    public static final String IDLE_POLICY_PARK_NS = "euhedral.fragment.idle.parkTimeValuesNs";
    public static final String EXEC_CONTENTION_THRESHOLDS = "euhedral.fragment.execution.contentionThresholds";
    public static final String EXEC_BODY_COST_WEIGHTS = "euhedral.fragment.execution.bodyCostWeights";
    public static final String EXEC_CONTENTION_POLICY = "euhedral.fragment.execution.contentionPolicy";

    public static final long DEFAULT_PARK_NS = 15_000L;
    public static final long DEFAULT_LOW_CONTENTION_THRESHOLD = 650_000L; // 65%
    public static final long DEFAULT_HIGH_CONTENTION_THRESHOLD = 980_000L; // 98%

    public final ContentionThresholds idleContentionThresholds;
    public final List<BodyCostThresholds> idleBodyCostThresholds;
    public final List<IdlePolicy> idleTimeNs;
    public final ContentionThresholds execContentionThresholds;
    public final List<BodyCostThresholds> execBodyCostThresholds;
    public final List<ExecutionPolicy> executionPolicies;

    public final long maxBodyCostThreshold;

    public FragmentControlConfig(
            ContentionThresholds idleContentionThresholds,
            List<BodyCostWeights> idleBodyCostWeights,
            List<IdlePolicy> idleTimeNs,
            ContentionThresholds execContentionThresholds,
            List<BodyCostWeights> execBodyCostWeights,
            List<ExecutionPolicy> executionPolicies) {
        Objects.requireNonNull(idleContentionThresholds);
        Objects.requireNonNull(idleBodyCostWeights);
        Objects.requireNonNull(idleTimeNs);
        Objects.requireNonNull(execContentionThresholds);
        Objects.requireNonNull(execBodyCostWeights);
        Objects.requireNonNull(executionPolicies);

        if (idleBodyCostWeights.size() != 5) {
            throw new IllegalArgumentException("Length of idleBodyCostWeights must be 5");
        }
        if (execBodyCostWeights.size() != 5) {
            throw new IllegalArgumentException("Length of execBodyCostWeights must be 5");
        }

        this.idleContentionThresholds = idleContentionThresholds;
        this.execContentionThresholds = execContentionThresholds;
        this.executionPolicies = List.copyOf(executionPolicies);
        this.idleTimeNs = List.copyOf(idleTimeNs);

        BodyCostThresholds[] idle = new BodyCostThresholds[5];
        BodyCostThresholds[] exec = new BodyCostThresholds[5];

        MicroCalibrator calibrator = new MicroCalibrator();
        calibrator.warmup();
        long max = 0;
        for (int i = 0; i < idleBodyCostWeights.size(); i++) {
            idle[i] = new BodyCostThresholds(calibrator, idleBodyCostWeights.get(i));
            exec[i] = new BodyCostThresholds(calibrator, execBodyCostWeights.get(i));
            max = Math.max(max, Math.max(idle[i].h, exec[i].h));
        }
        this.idleBodyCostThresholds = List.of(idle);
        this.execBodyCostThresholds = List.of(exec);
        this.maxBodyCostThreshold = max;
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
                throw new IllegalArgumentException(IDLE_POLICY_PARK_NS
                        + " must have 5 sets of 5 comma-separated 64-bit integers within each set. Formatting:"
                        + " propertyName=\"[],[],[],[]\"");
            }

            IdlePolicy[] policies = new IdlePolicy[sets.length];
            for (int i = 0; i < policies.length; i++) {
                String[] tokens = sets[i].split(",");
                if (tokens.length != 5) {
                    throw new IllegalArgumentException(IDLE_POLICY_PARK_NS
                            + " must have 5 sets of 5 comma-separated 64-bit integers within each set."
                            + " Formatting: propertyName=\"[],[],[],[]\"");
                }
                long xs = Long.parseLong(tokens[0]);
                long s = Long.parseLong(tokens[1]);
                long m = Long.parseLong(tokens[2]);
                long h = Long.parseLong(tokens[3]);
                long xh = Long.parseLong(tokens[4]);

                if (xs > s || s > m || m > h || h > xh) {
                    throw new IllegalArgumentException(IDLE_POLICY_PARK_NS
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
                throw new IllegalArgumentException(EXEC_CONTENTION_POLICY
                        + " must have 5 sets of 5 comma-separated enums (DIRECT, STAGED, SKIP) within each set."
                        + " Formatting: propertyName=\"[],[],[],[]\"");
            }

            ExecutionPolicy[] policies = new ExecutionPolicy[sets.length];
            for (int i = 0; i < policies.length; i++) {
                String[] tokens = sets[i].split(",");
                if (tokens.length != 5) {
                    throw new IllegalArgumentException(EXEC_CONTENTION_POLICY
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
