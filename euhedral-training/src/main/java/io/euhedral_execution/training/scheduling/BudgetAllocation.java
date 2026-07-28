package io.euhedral_execution.training.scheduling;

public record BudgetAllocation(int policyBudget, int fixedAnchors, int newExploration,
        int carryForward, int leaderRevalidation, int disagreementAudit) {
    public BudgetAllocation {
        if (policyBudget <= fixedAnchors || fixedAnchors < 0 || newExploration < 0
                || carryForward < 0 || leaderRevalidation < 0 || disagreementAudit < 0
                || fixedAnchors + newExploration + carryForward + leaderRevalidation
                + disagreementAudit != policyBudget) {
            throw new IllegalArgumentException("Invalid budget allocation");
        }
    }

    public int residual() {
        return policyBudget - fixedAnchors;
    }
}
