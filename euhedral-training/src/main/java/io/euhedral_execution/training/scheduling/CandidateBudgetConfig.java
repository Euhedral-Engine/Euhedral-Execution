package io.euhedral_execution.training.scheduling;

public record CandidateBudgetConfig(int policyBudget, int newExplorationWeight,
        int carryForwardWeight, int leaderRevalidationWeight, int disagreementAuditWeight) {
    public CandidateBudgetConfig {
        if (policyBudget <= 0) {
            throw new IllegalArgumentException("Policy budget must be positive");
        }
    }

    public static CandidateBudgetConfig defaults(int policyBudget) {
        return new CandidateBudgetConfig(policyBudget, 68, 25, 2, 5);
    }
}
