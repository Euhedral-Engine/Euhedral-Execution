package io.euhedral_execution.training.data;

import java.util.Arrays;

public final class PolicyVector {

    public static final int WIDTH = 28;
    private final double[] weights;
    private final PolicyId id;
    private final int hashCode;

    private PolicyVector(double[] weights) {
        if (weights.length != WIDTH) {
            throw new IllegalArgumentException("Expected 28 weights");
        }
        this.weights = weights.clone();
        for (double weight : this.weights) {
            if (!Double.isFinite(weight)) {
                throw new IllegalArgumentException("Policy weights must be finite");
            }
        }
        id = PolicyId.fromWeights(this.weights);
        hashCode = Arrays.hashCode(Arrays.stream(this.weights)
                .mapToLong(Double::doubleToRawLongBits).toArray());
    }

    public static PolicyVector of(double[] weights) {
        return new PolicyVector(weights);
    }

    public PolicyId id() {
        return id;
    }

    public double weight(int index) {
        return weights[index];
    }

    public double[] copyWeights() {
        return weights.clone();
    }

    public boolean bitwiseEquals(PolicyVector other) {
        if (other == null) {
            return false;
        }
        for (int i = 0; i < WIDTH; i++) {
            if (Double.doubleToRawLongBits(weights[i])
                    != Double.doubleToRawLongBits(other.weights[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof PolicyVector other && bitwiseEquals(other);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
