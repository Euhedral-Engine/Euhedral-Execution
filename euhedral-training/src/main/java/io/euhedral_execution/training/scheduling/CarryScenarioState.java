package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.data.SourceScenario;

public record CarryScenarioState(SourceScenario scenario, CoverageState state, int attemptCount,
        long lastAttemptIteration, long nextEligibleIteration) {
    public CarryScenarioState {
        if (attemptCount < 0 || lastAttemptIteration < 0 || nextEligibleIteration < 0) {
            throw new IllegalArgumentException("Invalid carry attempt state");
        }
        if (state == CoverageState.VALID && nextEligibleIteration != 0) {
            nextEligibleIteration = 0;
        }
    }

    public CarryScenarioState attempted(long iteration, CoverageState nextState) {
        int nextAttemptCount = Math.addExact(attemptCount, 1);
        if (nextState == CoverageState.VALID) {
            return new CarryScenarioState(scenario, nextState, nextAttemptCount, iteration, 0);
        }
        long delay = 1L << Math.min(nextAttemptCount - 1, 3);
        return new CarryScenarioState(scenario, nextState, nextAttemptCount, iteration,
                Math.addExact(iteration, Math.min(delay, 8L)));
    }
}
