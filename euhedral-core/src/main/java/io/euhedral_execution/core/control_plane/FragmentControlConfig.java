package io.euhedral_execution.core.control_plane;

import io.euhedral_execution.core.config.FragmentDecisionWeights;
import io.euhedral_execution.core.config.FragmentDecisionWeights.BodyCostWeights;
import io.euhedral_execution.core.config.FragmentDecisionWeights.IdlePolicy;
import io.euhedral_execution.core.utils.MicroCalibrator;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public final class FragmentControlConfig {
    public static final String IDLE_BODY_COST_WEIGHTS = "euhedral.fragment.idle.bodyCostThresholdsPath";
    public static final String IDLE_POLICY_PARK_NS = "euhedral.fragment.idle.parkTimeValuesNsPath";
    public static final String PRODUCTIVITY_THRESHOLD_WEIGHT = "euhedral.productivity.thresholdWeight";
    public static final String PRODUCTIVITY_GATE_MODE = "euhedral.calibration.productivityGateMode";
    public static final String FORCED_ACTIVE_PARTICIPANT_COUNT = "euhedral.calibration.forcedActiveParticipantCount";
    public static final String CACHE_PARK_NS = "euhedral.fragment.cache.parkNs";

    public static final long DEFAULT_PARK_NS = 15_000L;
    public static final long DEFAULT_CACHE_PARK_NS = 15_000L;
    public static final String CACHE_ACTUATOR_VERSION = "cache-v1";
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
        CACHE,
        DIRECT,
        STAGED,
        SKIP_THEN_DIRECT,
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
            this.xs = calibrator.benchmark(weights.xs());
            if (weights.xs() == weights.s()) {
                this.s = this.xs;
            } else {
                this.s = calibrator.benchmark(weights.s());
            }
            if (weights.s() == weights.m()) {
                this.m = this.s;
            } else {
                this.m = calibrator.benchmark(weights.m());
            }
            if (weights.m() == weights.h()) {
                this.h = this.m;
            } else {
                this.h = calibrator.benchmark(weights.h());
            }
        }
    }
}
