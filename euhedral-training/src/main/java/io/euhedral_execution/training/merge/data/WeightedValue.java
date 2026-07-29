package io.euhedral_execution.training.merge.data;

public record WeightedValue<K extends Comparable<? super K>>(
        double value, double weight, K tieBreaker) {

    public WeightedValue {
        if (!Double.isFinite(value) || !Double.isFinite(weight) || weight <= 0
                || tieBreaker == null) {
            throw new IllegalArgumentException("Invalid weighted value");
        }
    }
}
