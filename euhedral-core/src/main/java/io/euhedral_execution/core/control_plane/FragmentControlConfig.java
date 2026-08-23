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
    public static final String EXEC_CONTENTION_POLICY = "euhedral.fragment.execution.contentionPolicyPath";

    public static final int IDLE_WEIGHT_SETS = 4;
    public static final int EXEC_WEIGHT_SETS = 4;
    public static final int POLICY_COUNT = 5;

    public static final long DEFAULT_PARK_NS = 15_000L;

    public final ContentionThresholds idleContentionThresholds;
    public final BodyCostThresholds idleBodyCostThresholds;
    public final IdlePolicy idleTimeNs;

    public FragmentControlConfig(@NonNull FragmentDecisionWeights weights) {
        Objects.requireNonNull(weights);

        this.idleContentionThresholds = ContentionThresholds.DEFAULTS;
        this.idleTimeNs = weights.idleTimeNs();

        MicroCalibrator calibrator = new MicroCalibrator();
        calibrator.warmup();
        this.idleBodyCostThresholds = new BodyCostThresholds(calibrator, weights.idleBodyCostWeights());
    }

    /// Execution strategies selected only at completed-batch boundaries.
    public enum ExecutionPath {
        DIRECT,
        STAGED,
        SKIP_THEN_DIRECT,
        SKIP_THEN_STAGED
    }

    public record ContentionThresholds(long xsContention, long sContention, long mContention, long hContention) {
        public static final ContentionThresholds DEFAULTS;

        static {
            String prop = System.getProperty(IDLE_CONTENTION_THRESHOLDS);
            if (prop != null) {
                ObjectMapper mapper = new ObjectMapper();
                try {
                    DEFAULTS = mapper.readValue(new File(prop), ContentionThresholds.class);
                } catch (Exception e) {
                    throw new ExceptionInInitializerError(e);
                }
            } else {
                DEFAULTS = new ContentionThresholds(
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
        public static final BodyCostThresholds DEFAULTS;

        static {
            MicroCalibrator calibrator = new MicroCalibrator();
            calibrator.warmup();

            DEFAULTS = new BodyCostThresholds(calibrator, BodyCostWeights.DEFAULTS);
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
        public static final BodyCostWeights DEFAULTS;

        static {
            String prop = System.getProperty(IDLE_BODY_COST_WEIGHTS);
            if (prop != null) {
                ObjectMapper mapper = new ObjectMapper();
                try {
                    DEFAULTS = mapper.readValue(new File(prop), BodyCostWeights.class);
                } catch (Exception e) {
                    throw new ExceptionInInitializerError(e);
                }
            } else {
                DEFAULTS = new BodyCostWeights(96, 128, 216, 288);
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
        public static final IdlePolicy DEFAULT;

        static {
            String prop = System.getProperty(IDLE_POLICY_PARK_NS);
            if (prop != null) {
                ObjectMapper mapper = new ObjectMapper();
                try {
                    DEFAULT = mapper.readValue(new File(prop), IdlePolicy.class);
                } catch (Exception e) {
                    throw new ExceptionInInitializerError(e);
                }
            } else {
                DEFAULT = new IdlePolicy(1_000, 0, 5_000, 5_000, 5_000); // XH Contention
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
