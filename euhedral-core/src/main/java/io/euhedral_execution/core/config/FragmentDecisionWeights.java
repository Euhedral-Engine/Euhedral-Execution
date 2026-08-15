package io.euhedral_execution.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.BodyCostWeights;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ContentionThresholds;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPolicy;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.IdlePolicy;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public record FragmentDecisionWeights(
        @NonNull ContentionThresholds idleContentionThresholds,
        @NonNull List<BodyCostWeights> idleBodyCostWeights,
        @NonNull List<IdlePolicy> idleTimeNs,
        @NonNull ContentionThresholds execContentionThresholds,
        @NonNull List<BodyCostWeights> execBodyCostWeights,
        @NonNull List<ExecutionPolicy> executionPolicies) {
    public static final FragmentDecisionWeights DEFAULT = new FragmentDecisionWeights(
            ContentionThresholds.IDLE_DEFAULTS,
            BodyCostWeights.IDLE_DEFAULTS,
            IdlePolicy.DEFAULT,
            ContentionThresholds.EXEC_DEFAULTS,
            BodyCostWeights.EXEC_DEFAULTS,
            ExecutionPolicy.DEFAULT);

    @JsonCreator
    public FragmentDecisionWeights {
        Objects.requireNonNull(idleContentionThresholds);
        Objects.requireNonNull(idleBodyCostWeights);
        Objects.requireNonNull(idleTimeNs);
        Objects.requireNonNull(execContentionThresholds);
        Objects.requireNonNull(execBodyCostWeights);
        Objects.requireNonNull(executionPolicies);
    }
}
