package io.euhedral_execution.training.merge.config;

import io.euhedral_execution.training.merge.enums.CalibrationAcceptance;
import java.util.Objects;

public record AggregationConfig(
        int minimumSuccessfulRepetitions,
        double minimumSuccessFraction,
        int bootstrapReplicates,
        long bootstrapSeed,
        CalibrationAcceptance calibrationAcceptance) {
    public AggregationConfig {
        Objects.requireNonNull(calibrationAcceptance);
        if (minimumSuccessfulRepetitions < 1
                || !Double.isFinite(minimumSuccessFraction)
                || minimumSuccessFraction <= 0
                || minimumSuccessFraction > 1
                || bootstrapReplicates < 1) {
            throw new IllegalArgumentException();
        }
    }

    public static AggregationConfig defaults() {
        return new AggregationConfig(3, 0.5, 1000, 0x6a09e667f3bcc909L, CalibrationAcceptance.STRONG_ONLY);
    }
}
