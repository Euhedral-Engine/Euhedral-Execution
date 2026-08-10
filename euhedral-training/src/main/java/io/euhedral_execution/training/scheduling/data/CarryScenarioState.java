package io.euhedral_execution.training.scheduling.data;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.data.ScenarioPrediction;
import io.euhedral_execution.training.scheduling.enums.CoverageState;
import java.util.Objects;
import java.util.OptionalInt;

public record CarryScenarioState(
        SourceScenario scenario,
        CoverageState coverage,
        int attemptCount,
        OptionalInt lastAttemptIteration,
        int nextEligibleIteration,
        ScenarioPrediction prediction) {
    public CarryScenarioState {
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(coverage);
        Objects.requireNonNull(lastAttemptIteration);
        Objects.requireNonNull(prediction);
        if (!scenario.equals(prediction.scenario())
                || attemptCount < 0
                || nextEligibleIteration < 0
                || lastAttemptIteration.isPresent() != (attemptCount > 0)
                || lastAttemptIteration.isPresent() && lastAttemptIteration.getAsInt() < 0) {
            throw new IllegalArgumentException("Invalid carry attempt state");
        }
        if (coverage == CoverageState.VALID) {
            nextEligibleIteration = 0;
        }
    }

    public CarryScenarioState attempted(int iteration, CoverageState nextState) {
        int nextAttemptCount = Math.addExact(attemptCount, 1);
        if (nextState == CoverageState.VALID) {
            return new CarryScenarioState(
                    scenario, nextState, nextAttemptCount, OptionalInt.of(iteration), 0, prediction);
        }
        long delay = 1L << Math.min(nextAttemptCount - 1, 3);
        return new CarryScenarioState(
                scenario,
                nextState,
                nextAttemptCount,
                OptionalInt.of(iteration),
                Math.toIntExact(Math.addExact(iteration, Math.min(delay, 8L))),
                prediction);
    }
}
