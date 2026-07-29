package io.euhedral_execution.training.learning.config;

import java.util.Locale;
import java.util.Set;

import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;

public final class ScenarioMemberSeeds {

    static final Set<String> TRAINING_KINDS =
            Set.of("PRODUCTION", "TEST_LOSO", "VALIDATION_CONTEXT_LOSO", "VALIDATION_COUNTS_LOEO");

    public static long derive(long modelSeed, String trainingKind, ScenarioFeatureSet featureSet,
            String foldId, int memberIndex) {
        if (!TRAINING_KINDS.contains(trainingKind) || foldId == null || foldId.isBlank()
                || memberIndex < 0) {
            throw new IllegalArgumentException("Invalid member seed identity");
        }
        String material =
                "scenario-ordinal-member-seed-v1\n" + "kind=" + trainingKind + "\n" + "feature="
                        + featureSet.schemaId() + "\n" + "fold=" + foldId + "\n" + "member="
                        + String.format(Locale.ROOT, "%04d", memberIndex) + "\n";
        return HasherApi.getHash(material, modelSeed);
    }

    public static int engineSeed(long memberSeed) {
        return Long.hashCode(memberSeed);
    }

    private ScenarioMemberSeeds() {
    }
}
