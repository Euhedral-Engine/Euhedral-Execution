package io.euhedral_execution.training.data;

import java.util.Objects;
import java.util.Set;

import io.euhedral_execution.training.data.enums.PolicyRole;

public record ScheduledPolicy(int schedulePosition, PolicyVector policy, Set<PolicyRole> roles) {

    public ScheduledPolicy {
        Objects.requireNonNull(policy);
        roles = Set.copyOf(roles);
        if (schedulePosition <= 0 || roles.isEmpty()) {
            throw new IllegalArgumentException("Invalid scheduled policy");
        }
    }
}
