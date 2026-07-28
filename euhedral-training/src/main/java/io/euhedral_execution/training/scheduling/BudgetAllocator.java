package io.euhedral_execution.training.scheduling;

public final class BudgetAllocator {
    private static final int NEW = 0;
    private static final int CARRY = 1;
    private static final int LEADER = 2;
    private static final int AUDIT = 3;
    private static final int[] TIE_ORDER = {CARRY, LEADER, AUDIT, NEW};

    public BudgetAllocation allocate(CandidateBudgetConfig config, int fixedAnchorCount) {
        if (fixedAnchorCount < 0 || config.policyBudget() <= fixedAnchorCount) {
            throw new IllegalArgumentException("Policy budget must exceed fixed anchors");
        }
        int residual = config.policyBudget() - fixedAnchorCount;
        int[] parts = HamiltonAllocator.allocate(residual, new int[]{
                config.newExplorationWeight(), config.carryForwardWeight(),
                config.leaderRevalidationWeight(), config.disagreementAuditWeight()
        }, TIE_ORDER);
        return new BudgetAllocation(config.policyBudget(), fixedAnchorCount, parts[NEW],
                parts[CARRY], parts[LEADER], parts[AUDIT]);
    }
}
