package io.euhedral_execution.training.packaging;

record PackageOmission(String semanticGroup, String reason, boolean requiredForCompleteRun)
        implements Comparable<PackageOmission> {
    PackageOmission {
        if (!semanticGroup.matches("MERGE|MODEL|SCHEDULE")
                || !reason.matches("NOT_YET_CALIBRATED|NOT_YET_TRAINED|"
                        + "NO_NORMAL_ITERATION_SCHEDULE_AT_CHECKPOINT|"
                        + "MODEL_REJECTED_BEFORE_SCHEDULING")) {
            throw new IllegalArgumentException("Invalid package omission");
        }
    }

    @Override
    public int compareTo(PackageOmission other) {
        return semanticGroup.compareTo(other.semanticGroup);
    }
}
