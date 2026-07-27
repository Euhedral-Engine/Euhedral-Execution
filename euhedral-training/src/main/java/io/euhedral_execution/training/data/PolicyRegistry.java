package io.euhedral_execution.training.data;

import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

public final class PolicyRegistry {
    private final NavigableMap<PolicyId, PolicyVector> policies = new TreeMap<>();

    public PolicyVector register(PolicyVector policy) {
        PolicyVector existing = policies.putIfAbsent(policy.id(), policy);
        if (existing != null && !existing.bitwiseEquals(policy)) {
            throw new PolicyHashCollisionException(policy.id());
        }
        return existing == null ? policy : existing;
    }

    public PolicyVector require(PolicyId id) {
        PolicyVector policy = policies.get(id);
        if (policy == null) {
            throw new IllegalArgumentException("Unknown policy " + id);
        }
        return policy;
    }

    public Collection<PolicyVector> policiesInIdOrder() {
        return List.copyOf(policies.values());
    }
}
