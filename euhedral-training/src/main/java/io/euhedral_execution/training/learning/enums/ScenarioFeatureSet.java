package io.euhedral_execution.training.learning.enums;

import java.util.ArrayList;
import java.util.List;

public enum ScenarioFeatureSet {
    POLICY_ONLY("policy-only-v1", 28, true),
    RATIO_ONLY("policy-ratio-v1", 29, false),
    RATIO_AND_COUNTS("policy-ratio-counts-v1", 31, false);

    private final String schemaId;
    private final int width;
    private final boolean ablationOnly;

    ScenarioFeatureSet(String schemaId, int width, boolean ablationOnly) {
        this.schemaId = schemaId;
        this.width = width;
        this.ablationOnly = ablationOnly;
    }

    public String schemaId() { return schemaId; }
    public int width() { return width; }
    public boolean ablationOnly() { return ablationOnly; }

    public List<String> featureNames() {
        ArrayList<String> names = new ArrayList<>(width);
        for (int i = 0; i < 28; i++) names.add("policy_weight_%02d".formatted(i));
        if (width > 28) names.add("source_core_ratio");
        if (width > 29) {
            names.add("source_count_log1p");
            names.add("available_physical_core_count_log1p");
        }
        return List.copyOf(names);
    }
}
