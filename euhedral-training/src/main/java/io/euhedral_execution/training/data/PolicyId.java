package io.euhedral_execution.training.data;

import io.euhedral_execution.hashing.HasherApi;

public record PolicyId(long value) implements Comparable<PolicyId> {

    public static PolicyId fromWeights(double[] weights) {
        if (weights == null || weights.length != PolicyVector.WIDTH) {
            throw new IllegalArgumentException("Expected 28 policy weights");
        }
        for (double weight : weights) {
            if (!Double.isFinite(weight)) {
                throw new IllegalArgumentException("Policy weights must be finite");
            }
        }
        return new PolicyId(HasherApi.getHash(weights));
    }

    public static PolicyId parse(String text) {
        if (text == null || !text.matches("p1-[0-9a-f]{16}")) {
            throw new IllegalArgumentException("Malformed policy ID: " + text);
        }
        return new PolicyId(Long.parseUnsignedLong(text.substring(3), 16));
    }

    public String canonical() {
        return "p1-" + String.format("%016x", value);
    }

    @Override
    public int compareTo(PolicyId other) {
        return Long.compareUnsigned(value, other.value);
    }

    @Override
    public String toString() {
        return canonical();
    }
}
