package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.benchmark.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.*;
import io.euhedral_execution.training.merge.CalibrationPlan;
import io.euhedral_execution.training.merge.MergeRecords.RobustPolicySummary;
import io.euhedral_execution.training.merge.RobustPolicyComparator;
import io.euhedral_execution.training.optimization.*;
import io.euhedral_execution.training.optimization.SchedulerSeeds;
import java.util.*;

public final class CandidateScheduler {
    public static SchedulePreparation prepare(int iteration, int candidateBudget,
            CalibrationPlan calibrationPlan, OptimizationCorpusView corpus,
            List<CarryForwardEntry> rescoredQueue, List<SourceScenario> selectedScenarios,
            CandidateBudgetConfig budgetConfig, PolicyCurvePredictor predictor) {
        BudgetAllocation allocation = new BudgetAllocator().allocate(budgetConfig,
                calibrationPlan.anchors().fixedAnchors().size());
        List<PolicyVector> anchors = calibrationPlan.anchors().fixedAnchors();
        Set<PolicyId> anchorIds = anchors.stream().map(PolicyVector::id)
                .collect(java.util.stream.Collectors.toSet());
        List<RobustPolicySummary> leaders = corpus.eligiblePolicies().stream()
                .filter(RobustPolicySummary::eligible)
                .filter(summary -> !anchorIds.contains(summary.policy().id()))
                .sorted(RobustPolicyComparator.BEST_FIRST)
                .limit(allocation.leaderRevalidation())
                .toList();
        TreeMap<SourceScenario, List<CarryForwardEntry>> carry = new TreeMap<>();
        CarryForwardQueue queue = new CarryForwardQueue(rescoredQueue);
        for (SourceScenario scenario : selectedScenarios) {
            carry.put(scenario, queue.selectFor(scenario, iteration, allocation.carryForward()));
        }
        ArrayList<PolicyVector> measured = new ArrayList<>();
        leaders.forEach(leader -> measured.add(leader.policy()));
        carry.values().forEach(rows -> rows.forEach(row -> measured.add(row.policy())));
        List<ScheduledPolicyPrediction> predictions = predictor.predict(measured).stream()
                .map(summary -> new ScheduledPolicyPrediction(summary.policy(), summary,
                        SchedulePolicyOrigin.MEASURED_LEADER))
                .toList();
        return new SchedulePreparation(iteration, candidateBudget, allocation, selectedScenarios,
                anchors, carry, leaders, predictions, allocation.newExploration(), 0,
                allocation.disagreementAudit());
    }

    public static IterationSchedule complete(String trainingRunId, long schedulerSeed,
            String commitSha, boolean dirtyWorkingTree, String cpuSetHex,
            BenchmarkExecutionConfig benchmarkConfig, SchedulePreparation preparation,
            CandidateGenerationResult generated) {
        ArrayList<ScheduledRun> runs = new ArrayList<>();
        ArrayList<ScenarioBudgetReport> reports = new ArrayList<>();
        ArrayList<ScheduledPolicyPrediction> predictions = new ArrayList<>(
                preparation.measuredPredictions());
        generated.disagreementAudits().forEach(candidate -> predictions.add(
                new ScheduledPolicyPrediction(candidate.policy(), candidate.prediction(),
                        SchedulePolicyOrigin.SCORE_BAND)));
        generated.baseExploration().forEach(candidate -> predictions.add(
                new ScheduledPolicyPrediction(candidate.policy(), candidate.prediction(),
                        scheduleOrigin(candidate.origin()))));
        generated.overflowExploration().forEach(candidate -> predictions.add(
                new ScheduledPolicyPrediction(candidate.policy(), candidate.prediction(),
                        scheduleOrigin(candidate.origin()))));
        ArrayList<PolicyId> admissions = predictions.stream()
                .filter(prediction -> prediction.origin() != SchedulePolicyOrigin.MEASURED_LEADER
                        && prediction.origin() != SchedulePolicyOrigin.MEASURED_CARRY)
                .sorted((l, r) -> PredictedPolicyComparator.BEST_FIRST.compare(l.prediction(),
                        r.prediction()))
                .limit(preparation.requestedAllocation().carryForward())
                .map(prediction -> prediction.policy().id())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (SourceScenario scenario : preparation.scenarios()) {
            List<ScheduledPolicy> policies = policiesForScenario(preparation, generated, scenario);
            String cohort = "c1-" + Long.toUnsignedString(SchedulerSeeds.hash(
                    "cohort\n" + trainingRunId + "\n" + preparation.iteration() + "\n"
                            + scenario.canonical() + "\n", schedulerSeed), 16);
            List<FrameSourceSeed> seeds = new ArrayList<>();
            for (int i = 0; i < scenario.sourceCount(); i++) {
                seeds.add(new FrameSourceSeed(i, SchedulerSeeds.hash("id\n" + cohort + i,
                        schedulerSeed), SchedulerSeeds.hash("route\n" + cohort + i,
                        schedulerSeed)));
            }
            BenchmarkParameters parameters = new BenchmarkParameters(
                    benchmarkConfig.expectedRepetitions(), benchmarkConfig.sampleDurationNanos(),
                    benchmarkConfig.livenessTimeoutNanos(), benchmarkConfig.framesPerSource(),
                    benchmarkConfig.resetTimeoutNanos(), benchmarkConfig.orderedFrames(), cpuSetHex,
                    seeds);
            String runId = "r1-" + Long.toUnsignedString(SchedulerSeeds.hash(
                    "run\n" + cohort + "\n" + commitSha + "\n" + dirtyWorkingTree + "\n",
                    schedulerSeed), 16);
            runs.add(new ScheduledRun(RunKind.NORMAL, scenario, runId, cohort, parameters,
                    policies));
            BudgetAllocation a = preparation.requestedAllocation();
            reports.add(new ScenarioBudgetReport(scenario, preparation.candidateBudget(),
                    a.fixedAnchors(), a.fixedAnchors(), a.carryForward(),
                    (int) policies.stream().filter(p -> p.roles().contains(PolicyRole.CARRY_FORWARD)).count(),
                    a.leaderRevalidation(),
                    (int) policies.stream().filter(p -> p.roles().contains(PolicyRole.LEADER_REVALIDATION)).count(),
                    a.disagreementAudit(),
                    (int) policies.stream().filter(p -> p.roles().contains(PolicyRole.DISAGREEMENT_AUDIT)).count(),
                    a.newExploration(),
                    (int) policies.stream().filter(p -> p.roles().contains(PolicyRole.EXPLORATION)).count(),
                    0, 0, 0, policies.size()));
        }
        return new IterationSchedule(preparation.iteration(), runs, predictions, admissions,
                reports, generated.nextSobolCursor());
    }

    private static List<ScheduledPolicy> policiesForScenario(SchedulePreparation preparation,
            CandidateGenerationResult generated, SourceScenario scenario) {
        ArrayList<RolePolicy> rows = new ArrayList<>();
        preparation.fixedAnchors().forEach(policy -> rows.add(new RolePolicy(policy,
                PolicyRole.FIXED_ANCHOR)));
        preparation.carryByScenario().getOrDefault(scenario, List.of()).forEach(entry ->
                rows.add(new RolePolicy(entry.policy(), PolicyRole.CARRY_FORWARD)));
        preparation.leaders().forEach(leader -> rows.add(new RolePolicy(leader.policy(),
                PolicyRole.LEADER_REVALIDATION)));
        generated.disagreementAudits().forEach(candidate -> rows.add(new RolePolicy(
                candidate.policy(), PolicyRole.DISAGREEMENT_AUDIT)));
        generated.baseExploration().forEach(candidate -> rows.add(new RolePolicy(candidate.policy(),
                PolicyRole.EXPLORATION)));
        generated.overflowExploration().forEach(candidate -> rows.add(new RolePolicy(
                candidate.policy(), PolicyRole.EXPLORATION)));
        LinkedHashMap<PolicyId, RolePolicy> unique = new LinkedHashMap<>();
        rows.forEach(row -> {
            if (unique.putIfAbsent(row.policy().id(), row) != null) {
                throw new IllegalArgumentException("Duplicate scheduled policy role");
            }
        });
        ArrayList<ScheduledPolicy> scheduled = new ArrayList<>();
        int position = 1;
        for (RolePolicy row : unique.values()) {
            scheduled.add(new ScheduledPolicy(position++, row.policy(), Set.of(row.role())));
        }
        if (scheduled.size() != preparation.candidateBudget()) {
            throw new IllegalStateException("Schedule does not fill policy budget");
        }
        return scheduled;
    }

    private record RolePolicy(PolicyVector policy, PolicyRole role) {
    }

    private static SchedulePolicyOrigin scheduleOrigin(CandidateOrigin origin) {
        return switch (origin) {
            case CMA_ES -> SchedulePolicyOrigin.CMA_ES;
            case SCORE_BAND -> SchedulePolicyOrigin.SCORE_BAND;
            case DIRECT_SOBOL -> SchedulePolicyOrigin.DIRECT_SOBOL;
        };
    }

    private CandidateScheduler() {
    }
}
