package io.euhedral_execution.core.control_plane;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import io.euhedral_execution.core.utils.MicroCalibrator;
import java.io.File;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public final class FragmentControlConfig {
    public static final String IDLE_BODY_COST_WEIGHTS = "euhedral.fragment.idle.bodyCostThresholdsPath";
    public static final String IDLE_POLICY_PARK_NS = "euhedral.fragment.idle.parkTimeValuesNsPath";
    public static final String PRODUCTIVITY_THRESHOLD_WEIGHT = "euhedral.productivity.thresholdWeight";
    public static final String PRODUCTIVITY_GATE_MODE = "euhedral.calibration.productivityGateMode";

    public static final long DEFAULT_PARK_NS = 15_000L;
    public static final int DEFAULT_PRODUCTIVITY_THRESHOLD_WEIGHT = 40;

    public final BodyCostThresholds idleBodyCostThresholds;
    public final IdlePolicy idleTimeNs;

    public FragmentControlConfig(@NonNull FragmentDecisionWeights weights) {
        Objects.requireNonNull(weights);

        this.idleTimeNs = weights.idleTimeNs();

        MicroCalibrator calibrator = new MicroCalibrator();
        calibrator.warmup();
        this.idleBodyCostThresholds = new BodyCostThresholds(calibrator, weights.idleBodyCostWeights());
    }

    /// Execution strategies selected only at completed-batch boundaries.
    public enum ExecutionPath {
        DIRECT,
        STAGED,
        SKIP_THEN_DIRECT
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
                DEFAULT = new IdlePolicy(50_000, 0, 0, 0, 0);
            }
        }

        public IdlePolicy {}
    }
}
