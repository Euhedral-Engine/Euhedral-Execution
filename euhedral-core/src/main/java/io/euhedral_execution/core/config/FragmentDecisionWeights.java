package io.euhedral_execution.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.control_plane.FragmentControlConfig;
import java.io.File;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public record FragmentDecisionWeights(
        @NonNull BodyCostWeights idleBodyCostWeights,
        @NonNull IdlePolicy idleTimeNs,
        @NonNull ParetoWeights paretoWeights) {
    public static final FragmentDecisionWeights DEFAULT =
            new FragmentDecisionWeights(BodyCostWeights.DEFAULTS, IdlePolicy.DEFAULT, ParetoWeights.DEFAULT);

    @JsonCreator
    public FragmentDecisionWeights {
        Objects.requireNonNull(idleBodyCostWeights);
        Objects.requireNonNull(idleTimeNs);
        paretoWeights = Objects.requireNonNullElse(paretoWeights, ParetoWeights.DEFAULT);
    }

    public record BodyCostWeights(int xs, int s, int m, int h) {
        public static final BodyCostWeights DEFAULTS;

        static {
            String prop = System.getProperty(FragmentControlConfig.IDLE_BODY_COST_WEIGHTS);
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
            String prop = System.getProperty(FragmentControlConfig.IDLE_POLICY_PARK_NS);
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
    }

    public record ParetoWeights(
            double activeWorkersWeight,
            double contentionPhrWeight,
            double contentionWorkersWeight,
            double phrWeight,
            double bodyPhrWeight,
            double bodyWorkersWeight,
            double registeredWorkersPhrWeight,
            double registeredActiveWorkersWeight) {
        public static final ParetoWeights DEFAULT = new ParetoWeights(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0);
    }
}
