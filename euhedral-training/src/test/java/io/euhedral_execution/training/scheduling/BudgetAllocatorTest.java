package io.euhedral_execution.training.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.scheduling.config.CandidateBudgetConfig;
import io.euhedral_execution.training.scheduling.data.BudgetAllocation;
import org.junit.jupiter.api.Test;

class BudgetAllocatorTest {
    @Test
    void defaultHamiltonAllocationFillsEveryResidual() {
        CandidateBudgetConfig config = CandidateBudgetConfig.defaults();
        for (int residual = 1; residual <= 256; residual++) {
            BudgetAllocation allocation = BudgetAllocator.allocate(residual + 5, 5, config);
            assertThat(allocation.total()).isEqualTo(residual + 5);
            assertThat(new int[]{allocation.exploration(), allocation.carryForward(),
                    allocation.leaderRevalidation(), allocation.disagreementAudit()})
                    .containsExactly(independent(residual, new int[]{68, 25, 2, 5}));
        }
    }

    @Test
    void reservesAnchorsAndRejectsInvalidWeightsAndBudgets() {
        BudgetAllocation allocation = BudgetAllocator.allocate(9, 4,
                new CandidateBudgetConfig(1, 1, 1, 1));
        assertThat(allocation.fixedAnchors()).isEqualTo(4);
        assertThat(allocation.carryForward()).isEqualTo(2);
        assertThatThrownBy(() -> BudgetAllocator.allocate(4, 4,
                CandidateBudgetConfig.defaults())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CandidateBudgetConfig(0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int[] independent(int residual, int[] weights) {
        int sum = java.util.Arrays.stream(weights).sum();
        int[] result = new int[weights.length];
        int[] remainder = new int[weights.length];
        int assigned = 0;
        for (int i = 0; i < weights.length; i++) {
            result[i] = residual * weights[i] / sum;
            remainder[i] = residual * weights[i] % sum;
            assigned += result[i];
        }
        int[] tieOrder = {1, 2, 3, 0};
        while (assigned++ < residual) {
            int best = tieOrder[0];
            for (int candidate : tieOrder) {
                if (remainder[candidate] > remainder[best]) {
                    best = candidate;
                }
            }
            result[best]++;
            remainder[best] = -1;
        }
        return result;
    }
}
