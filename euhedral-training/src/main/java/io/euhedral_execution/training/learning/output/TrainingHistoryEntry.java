package io.euhedral_execution.training.learning.output;

import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import java.util.Objects;
import java.util.OptionalDouble;

public record TrainingHistoryEntry(
        String trainingKind,
        String foldId,
        ScenarioFeatureSet featureSet,
        int memberIndex,
        long memberSeed,
        int epoch,
        double validationMacroMae,
        OptionalDouble validationMacroSpearman,
        double validationWeightedBce,
        boolean selectedEpoch)
        implements Comparable<TrainingHistoryEntry> {

    public TrainingHistoryEntry {
        Objects.requireNonNull(trainingKind);
        Objects.requireNonNull(foldId);
        Objects.requireNonNull(featureSet);
        Objects.requireNonNull(validationMacroSpearman);
        if (memberIndex < 0
                || epoch < 0
                || !Double.isFinite(validationMacroMae)
                || validationMacroMae < 0
                || !Double.isFinite(validationWeightedBce)
                || validationWeightedBce < 0
                || validationMacroSpearman.isPresent()
                        && (!Double.isFinite(validationMacroSpearman.getAsDouble())
                                || validationMacroSpearman.getAsDouble() < -1
                                || validationMacroSpearman.getAsDouble() > 1)) {
            throw new IllegalArgumentException("Invalid training history entry");
        }
    }

    @Override
    public int compareTo(TrainingHistoryEntry other) {
        int result = trainingKind.compareTo(other.trainingKind);
        if (result == 0) {
            result = foldId.compareTo(other.foldId);
        }
        if (result == 0) {
            result = featureSet.schemaId().compareTo(other.featureSet.schemaId());
        }
        if (result == 0) {
            result = Integer.compare(memberIndex, other.memberIndex);
        }
        return result != 0 ? result : Integer.compare(epoch, other.epoch);
    }
}
