package io.euhedral_execution.training.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.learning.data.ScenarioPrediction;
import io.euhedral_execution.training.scheduling.data.CarryForwardEntry;
import io.euhedral_execution.training.scheduling.data.CarryScenarioState;
import io.euhedral_execution.training.scheduling.data.IterationSchedule;
import io.euhedral_execution.training.scheduling.enums.CoverageState;
import io.euhedral_execution.training.scheduling.fixtures.SchedulingFixtures;
import java.util.List;
import java.util.OptionalInt;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CarryForwardQueueTest {
    private static CarryForwardEntry entry(
            int seed, CoverageState first, CoverageState second, CoverageState third, double low) {
        var policy = SchedulingFixtures.policy(seed);
        TreeMap<io.euhedral_execution.training.data.SourceScenario, CarryScenarioState> states = new TreeMap<>();
        int index = 0;
        for (var scenario : SchedulingFixtures.SCENARIOS) {
            CoverageState coverage = new CoverageState[] {first, second, third}[index++];
            ScenarioPrediction prediction = new ScenarioPrediction(
                    scenario, Math.min(0.9, low + 0.1), 0.1, low, Math.min(1, low + 0.2), 0.2, low, 0.1, 0.1);
            states.put(scenario, new CarryScenarioState(scenario, coverage, 0, OptionalInt.empty(), 0, prediction));
        }
        return new CarryForwardEntry(policy, 1, 1, states);
    }

    @Test
    void prioritizesCoverageThenPessimisticQualityAndAppliesBackoff() {
        CarryForwardEntry lowCoverage =
                entry(1, CoverageState.MISSING, CoverageState.MISSING, CoverageState.MISSING, 0.9);
        CarryForwardEntry highCoverage = entry(2, CoverageState.VALID, CoverageState.VALID, CoverageState.MISSING, 0.2);
        assertThat(CarryForwardQueue.selectForScenario(List.of(lowCoverage, highCoverage), SchedulingFixtures.S3, 1, 2))
                .containsExactly(highCoverage, lowCoverage);

        CarryScenarioState state = highCoverage.scenarios().get(SchedulingFixtures.S3);
        int[] delays = {1, 2, 4, 8, 8};
        int iteration = 10;
        for (int delay : delays) {
            state = state.attempted(iteration, CoverageState.REJECTED);
            assertThat(state.nextEligibleIteration()).isEqualTo(iteration + delay);
            iteration++;
        }
    }

    @Test
    void rescoreRetainsEntryUntilCorpusMarksItEligible() {
        CarryForwardEntry entry = entry(3, CoverageState.VALID, CoverageState.MISSING, CoverageState.MISSING, 0.4);
        var rescored = CarryForwardQueue.rescore(List.of(entry), SchedulingFixtures.predictor(), 7);
        assertThat(rescored).hasSize(1);
        assertThat(rescored.getFirst().lastUpdatedIteration()).isEqualTo(7);
        var eligible = SchedulingFixtures.corpus(List.of(SchedulingFixtures.eligible(entry.policy(), 0.5)));
        assertThat(CarryForwardQueue.reconcile(
                        rescored,
                        eligible,
                        new IterationSchedule("run", 7, List.of(), List.of(), List.of(), List.of(), 0),
                        7))
                .isEmpty();
    }
}
