package io.euhedral_execution.core.control_plane;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import io.euhedral_execution.core.utils.MicroCalibrator;
import java.io.File;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public final class FragmentControlConfig {
    public static final String IDLE_CONTENTION_THRESHOLDS = "euhedral.fragment.idle.contentionThresholdsPath";
    public static final String IDLE_BODY_COST_WEIGHTS = "euhedral.fragment.idle.bodyCostThresholdsPath";
    public static final String IDLE_POLICY_PARK_NS = "euhedral.fragment.idle.parkTimeValuesNsPath";
    public static final String EXEC_CONTENTION_THRESHOLDS = "euhedral.fragment.execution.contentionThresholdsPath";
    public static final String EXEC_BODY_COST_WEIGHTS = "euhedral.fragment.execution.bodyCostWeightsPath";
    public static final String EXEC_CONTENTION_POLICY = "euhedral.fragment.execution.contentionPolicyPath";

    public static final int IDLE_WEIGHT_SETS = 4;
    public static final int EXEC_WEIGHT_SETS = 4;
    public static final int POLICY_COUNT = 5;

    public static final long DEFAULT_PARK_NS = 15_000L;

    public final ContentionThresholds idleContentionThresholds;
    public final List<BodyCostThresholds> idleBodyCostThresholds;
    public final List<IdlePolicy> idleTimeNs;
    public final ContentionThresholds execContentionThresholds;
    public final List<BodyCostThresholds> execBodyCostThresholds;
    public final List<ExecutionPolicy> executionPolicies;

    public final long maxBodyCostThreshold;

    public FragmentControlConfig(@NonNull FragmentDecisionWeights weights) {
        Objects.requireNonNull(weights);

        this.idleContentionThresholds = weights.idleContentionThresholds();
        this.execContentionThresholds = weights.execContentionThresholds();
        this.executionPolicies = weights.executionPolicies();
        this.idleTimeNs = weights.idleTimeNs();

        BodyCostThresholds[] idle = new BodyCostThresholds[IDLE_WEIGHT_SETS];
        BodyCostThresholds[] exec = new BodyCostThresholds[EXEC_WEIGHT_SETS];

        MicroCalibrator calibrator = new MicroCalibrator();
        calibrator.warmup();
        long max = 0;
        for (int i = 0; i < weights.idleBodyCostWeights().size(); i++) {
            idle[i] = new BodyCostThresholds(
                    calibrator, weights.idleBodyCostWeights().get(i));
            exec[i] = new BodyCostThresholds(
                    calibrator, weights.execBodyCostWeights().get(i));
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
        SKIP_THEN_DIRECT,
        SKIP_THEN_STAGED
    }

    public record ContentionThresholds(long xsContention, long sContention, long mContention, long hContention) {
        public static final ContentionThresholds IDLE_DEFAULTS;
        public static final ContentionThresholds EXEC_DEFAULTS;

        static {
            String prop = System.getProperty(IDLE_CONTENTION_THRESHOLDS);
            if (prop != null) {
                ObjectMapper mapper = new ObjectMapper();
                try {
                    IDLE_DEFAULTS = mapper.readValue(new File(prop), ContentionThresholds.class);
                } catch (Exception e) {
                    throw new ExceptionInInitializerError(e);
                }
            } else {
                IDLE_DEFAULTS = new ContentionThresholds(
                        50_000, // 5%
                        350_000, // 35%
                        650_000, // 65%
                        850_000); // 85%
            }

            prop = System.getProperty(EXEC_CONTENTION_THRESHOLDS);
            if (prop != null) {
                ObjectMapper mapper = new ObjectMapper();
                try {
                    EXEC_DEFAULTS = mapper.readValue(new File(prop), ContentionThresholds.class);
                } catch (Exception e) {
                    throw new ExceptionInInitializerError(e);
                }
            } else {
                EXEC_DEFAULTS = new ContentionThresholds(
                        50_000, // 5%
                        350_000, // 35%
                        650_000, // 65%
                        850_000); // 85%
            }
        }

        @JsonCreator
        public ContentionThresholds {
            if (hContention > 1_000_000L
                    || xsContention > sContention
                    || sContention > mContention
                    || mContention > hContention) {
                throw new IllegalArgumentException("Parameters must be values from [0..1,000,000] in increasing order");
            }
        }
    }

    public static final class BodyCostThresholds {
        public static final List<BodyCostThresholds> IDLE_DEFAULTS;
        public static final List<BodyCostThresholds> EXEC_DEFAULTS;

        static {
            BodyCostThresholds[] idle = new BodyCostThresholds[IDLE_WEIGHT_SETS];
            BodyCostThresholds[] exec = new BodyCostThresholds[EXEC_WEIGHT_SETS];

            MicroCalibrator calibrator = new MicroCalibrator();
            calibrator.warmup();

            for (int i = 0; i < idle.length; i++) {
                idle[i] = new BodyCostThresholds(calibrator, BodyCostWeights.IDLE_DEFAULTS.get(i));
            }
            for (int i = 0; i < exec.length; i++) {
                exec[i] = new BodyCostThresholds(calibrator, BodyCostWeights.EXEC_DEFAULTS.get(i));
            }

            IDLE_DEFAULTS = List.of(idle);
            EXEC_DEFAULTS = List.of(exec);
        }

        public final long xs;
        public final long s;
        public final long m;
        public final long h;

        public BodyCostThresholds(@NonNull MicroCalibrator calibrator, @NonNull BodyCostWeights weights) {
            Objects.requireNonNull(calibrator);
            Objects.requireNonNull(weights);
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
                ObjectMapper mapper = new ObjectMapper();
                try {
                    IDLE_DEFAULTS = List.of(mapper.readValue(new File(prop), BodyCostWeights[].class));
                } catch (Exception e) {
                    throw new ExceptionInInitializerError(e);
                }
                if (IDLE_DEFAULTS.size() != IDLE_WEIGHT_SETS) {
                    throw new IllegalArgumentException(
                            String.format("There can only be %d sets of idle weights", IDLE_WEIGHT_SETS));
                }
            } else {
                IDLE_DEFAULTS = List.of(
                        new BodyCostWeights(96, 128, 216, 288), // XS Contention
                        new BodyCostWeights(96, 128, 216, 288), // S Contention
                        new BodyCostWeights(96, 128, 216, 288), // M Contention
                        new BodyCostWeights(96, 128, 216, 288)); // H Contention
            }

            prop = System.getProperty(EXEC_BODY_COST_WEIGHTS);
            if (prop != null) {
                ObjectMapper mapper = new ObjectMapper();
                try {
                    EXEC_DEFAULTS = List.of(mapper.readValue(new File(prop), BodyCostWeights[].class));
                } catch (Exception e) {
                    throw new ExceptionInInitializerError(e);
                }
                if (EXEC_DEFAULTS.size() != EXEC_WEIGHT_SETS) {
                    throw new IllegalArgumentException(
                            String.format("There can only be %d sets of execution weights", EXEC_WEIGHT_SETS));
                }
            } else {
                EXEC_DEFAULTS = List.of(
                        new BodyCostWeights(96, 128, 216, 288), // XS Contention
                        new BodyCostWeights(96, 128, 216, 288), // S Contention
                        new BodyCostWeights(96, 128, 216, 288), // M Contention
                        new BodyCostWeights(96, 128, 216, 288)); // H Contention
            }
        }

        @JsonCreator
        public BodyCostWeights {
            if (xs > s || s > m || m > h) {
                throw new IllegalStateException("Body cost weights must be in ascending order");
            }
        }
    }

    public record IdlePolicy(long xsPark, long sPark, long mPark, long hPark, long xhPark) {
        public static final List<IdlePolicy> DEFAULT;

        static {
            String prop = System.getProperty(IDLE_POLICY_PARK_NS);
            if (prop != null) {
                ObjectMapper mapper = new ObjectMapper();
                try {
                    DEFAULT = List.of(mapper.readValue(new File(prop), IdlePolicy[].class));
                } catch (Exception e) {
                    throw new ExceptionInInitializerError(e);
                }
                if (DEFAULT.size() != POLICY_COUNT) {
                    throw new IllegalArgumentException(
                            String.format("There can only be %d sets of idle policies", POLICY_COUNT));
                }
            } else {
                DEFAULT = List.of(
                        new IdlePolicy(0, 0, 0, 0, 0), // XS Contention
                        new IdlePolicy(0, 0, 0, 0, 0), // S Contention
                        new IdlePolicy(0, 0, 0, 0, 0), // M Contention
                        new IdlePolicy(0, 0, 0, 0, 0), // H Contention
                        new IdlePolicy(1_000, 15_000, 5_000, 5_000, 5_000) // XH Contention
                        );
            }
        }

        @JsonCreator
        public IdlePolicy {}
    }

    public record ExecutionPolicy(
            ExecutionPath xsBody, ExecutionPath sBody, ExecutionPath mBody, ExecutionPath hBody, ExecutionPath xhBody) {
        public static final List<ExecutionPolicy> DEFAULT;

        static {
            String prop = System.getProperty(EXEC_CONTENTION_POLICY);
            if (prop != null) {
                ObjectMapper mapper = new ObjectMapper();
                try {
                    DEFAULT = List.of(mapper.readValue(new File(prop), ExecutionPolicy[].class));
                } catch (Exception e) {
                    throw new ExceptionInInitializerError(e);
                }
                if (DEFAULT.size() != POLICY_COUNT) {
                    throw new IllegalArgumentException(
                            String.format("There can only be %d sets of execution policies", POLICY_COUNT));
                }
            } else {
                DEFAULT = List.of(
                        new ExecutionPolicy(
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT),
                        new ExecutionPolicy(
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT),
                        new ExecutionPolicy(
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT),
                        new ExecutionPolicy(
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT,
                                ExecutionPath.DIRECT),
                        new ExecutionPolicy(
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED));
            }
        }

        @JsonCreator
        public ExecutionPolicy {}
    }
}
