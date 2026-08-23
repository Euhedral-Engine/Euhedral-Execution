package io.euhedral_execution.core.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.BodyCostWeights;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.IdlePolicy;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public record FragmentDecisionWeights(
        @NonNull BodyCostWeights idleBodyCostWeights,
        @NonNull IdlePolicy idleTimeNs) {
    public static final FragmentDecisionWeights DEFAULT =
            new FragmentDecisionWeights(BodyCostWeights.DEFAULTS, IdlePolicy.DEFAULT);

    @JsonCreator
    public FragmentDecisionWeights {
        Objects.requireNonNull(idleBodyCostWeights);
        Objects.requireNonNull(idleTimeNs);
    }
}
