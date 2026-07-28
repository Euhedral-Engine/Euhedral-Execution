package io.euhedral_execution.training.scheduling;

public record BudgetAllocation(int fixedAnchors, int exploration, int carryForward,
        int leaderRevalidation, int disagreementAudit) {
    public BudgetAllocation {
        if (fixedAnchors < 0 || exploration < 0
                || carryForward < 0 || leaderRevalidation < 0 || disagreementAudit < 0
                || fixedAnchors + exploration + carryForward + leaderRevalidation
                + disagreementAudit <= fixedAnchors) {
            throw new IllegalArgumentException("Invalid budget allocation");
        }
    }

    public int total() {
        return Math.addExact(fixedAnchors, Math.addExact(exploration,
                Math.addExact(carryForward,
                        Math.addExact(leaderRevalidation, disagreementAudit))));
    }
}
