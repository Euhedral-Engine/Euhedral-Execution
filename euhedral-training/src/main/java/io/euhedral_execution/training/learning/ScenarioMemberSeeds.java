package io.euhedral_execution.training.learning;

import io.euhedral_execution.hashing.HasherApi;
import java.util.Locale;
import java.util.Set;

final class ScenarioMemberSeeds {
    static final Set<String> TRAINING_KINDS = Set.of("PRODUCTION", "TEST_LOSO",
            "VALIDATION_CONTEXT_LOSO", "VALIDATION_COUNTS_LOEO");

    private ScenarioMemberSeeds() {
    }

    static long derive(long modelSeed, String trainingKind, ScenarioFeatureSet featureSet,
            String foldId, int memberIndex) {
        if (!TRAINING_KINDS.contains(trainingKind) || foldId == null || foldId.isBlank()
                || memberIndex < 0) {
            throw new IllegalArgumentException("Invalid member seed identity");
        }
        String material = "scenario-ordinal-member-seed-v1\n"
                + "kind=" + trainingKind + "\n"
                + "feature=" + featureSet.schemaId() + "\n"
                + "fold=" + foldId + "\n"
                + "member=" + String.format(Locale.ROOT, "%04d", memberIndex) + "\n";
        return HasherApi.getHash(material, modelSeed);
    }

    static int engineSeed(long memberSeed) {
        return (int) (memberSeed ^ (memberSeed >>> 32));
    }
}
