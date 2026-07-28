package io.euhedral_execution.training.learning;
public record ScenarioDatasetAudit(int policyCount, int requiredScenarioCount, int phase1RowCount,
        int includedStrongRowCount, int includedWeakRowCount, int weakExcludedRowCount,
        int missingRowCount, int noValidRunRowCount, int noAcceptedCalibrationRowCount,
        int nonRequiredRowCount) {
    public ScenarioDatasetAudit {
        if (policyCount < 0 || requiredScenarioCount <= 0 || phase1RowCount < 0
                || includedStrongRowCount < 0 || includedWeakRowCount < 0
                || weakExcludedRowCount < 0 || missingRowCount < 0 || noValidRunRowCount < 0
                || noAcceptedCalibrationRowCount < 0 || nonRequiredRowCount < 0)
            throw new IllegalArgumentException("Negative dataset audit count");
    }
}
