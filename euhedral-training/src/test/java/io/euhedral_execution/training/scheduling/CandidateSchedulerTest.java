package io.euhedral_execution.training.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.enums.PolicyRole;
import io.euhedral_execution.training.optimization.data.CandidateGenerationResult;
import io.euhedral_execution.training.optimization.data.PredictedCandidate;
import io.euhedral_execution.training.optimization.enums.CandidateOrigin;
import io.euhedral_execution.training.scheduling.config.CandidateBudgetConfig;
import io.euhedral_execution.training.scheduling.data.IterationSchedule;
import io.euhedral_execution.training.scheduling.data.ScheduledRun;
import io.euhedral_execution.training.scheduling.fixtures.SchedulingFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class CandidateSchedulerTest {
    @Test
    void fillsExactDisjointBudgetsAndSpreadsAnchors() {
        var anchor1 = SchedulingFixtures.policy(1);
        var anchor2 = SchedulingFixtures.policy(2);
        var leader1 = SchedulingFixtures.eligible(SchedulingFixtures.policy(3), 0.8);
        var leader2 = SchedulingFixtures.eligible(SchedulingFixtures.policy(4), 0.7);
        var incomplete = SchedulingFixtures.incomplete(SchedulingFixtures.policy(5),
                new TreeSet<>(List.of(SchedulingFixtures.S1)));
        var corpus = SchedulingFixtures.corpus(List.of(leader2, incomplete, leader1));
        var preparation = CandidateScheduler.prepare(1, 10,
                SchedulingFixtures.calibration(List.of(anchor1, anchor2)), corpus, List.of(),
                List.of(SchedulingFixtures.S1, SchedulingFixtures.S2),
                new CandidateBudgetConfig(1, 1, 1, 1), SchedulingFixtures.predictor());

        List<PredictedCandidate> audits = candidates(10, 2, CandidateOrigin.SCORE_BAND);
        List<PredictedCandidate> base = candidates(20, 2, CandidateOrigin.DIRECT_SOBOL);
        List<PredictedCandidate> overflow = candidates(30, 2, CandidateOrigin.DIRECT_SOBOL);
        var generated = new CandidateGenerationResult(audits, base, overflow, 100, 0, 2, 4, 0);
        IterationSchedule schedule = CandidateScheduler.complete("training", 77L,
                "0".repeat(40), false, "f",
                new BenchmarkExecutionConfig(1, 10, 5, 8, 100, false),
                preparation, generated);

        assertThat(schedule.runs()).hasSize(2);
        for (ScheduledRun run : schedule.runs()) {
            assertThat(run.policies()).hasSize(10);
            assertThat(run.policies().stream().map(policy -> policy.policy().id()).distinct())
                    .hasSize(10);
            assertThat(run.policies().get(2).roles()).containsExactly(PolicyRole.FIXED_ANCHOR);
            assertThat(run.policies().get(7).roles()).containsExactly(PolicyRole.FIXED_ANCHOR);
            assertThat(run.policies()).noneMatch(policy ->
                    policy.policy().id().equals(incomplete.policy().id())
                            && policy.roles().contains(PolicyRole.LEADER_REVALIDATION));
        }
        assertThat(schedule.budgetReports()).allSatisfy(report -> {
            assertThat(report.totalAssigned()).isEqualTo(10);
            assertThat(report.carryAssigned()).isZero();
            assertThat(report.carryTransferredToExploration()).isEqualTo(2);
            assertThat(report.explorationAssigned()).isEqualTo(4);
        });
    }

    private static List<PredictedCandidate> candidates(int start, int count,
            CandidateOrigin origin) {
        ArrayList<PredictedCandidate> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var policy = SchedulingFixtures.policy(start + i);
            result.add(new PredictedCandidate(policy,
                    SchedulingFixtures.prediction(policy, 0.5 + i * 0.01,
                            0.6 + i * 0.01, 0.7 + i * 0.01), origin));
        }
        return result;
    }
}
