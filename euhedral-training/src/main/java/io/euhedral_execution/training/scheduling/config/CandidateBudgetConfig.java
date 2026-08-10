package io.euhedral_execution.training.scheduling.config;

public record CandidateBudgetConfig(
        int explorationWeight, int carryForwardWeight, int leaderRevalidationWeight, int disagreementAuditWeight) {
    public CandidateBudgetConfig {
        if (explorationWeight < 0
                || carryForwardWeight < 0
                || leaderRevalidationWeight < 0
                || disagreementAuditWeight < 0) {
            throw new IllegalArgumentException("Budget weights must not be negative");
        }
        Math.addExact(
                Math.addExact(explorationWeight, carryForwardWeight),
                Math.addExact(leaderRevalidationWeight, disagreementAuditWeight));
        if (explorationWeight + carryForwardWeight + leaderRevalidationWeight + disagreementAuditWeight == 0) {
            throw new IllegalArgumentException("At least one budget weight is required");
        }
    }

    public static CandidateBudgetConfig defaults() {
        return new CandidateBudgetConfig(68, 25, 2, 5);
    }
}
