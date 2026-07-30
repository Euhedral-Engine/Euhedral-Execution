package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.BenchmarkParameters;
import io.euhedral_execution.training.data.FrameSourceSeed;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.ScheduledPolicy;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.enums.PolicyRole;
import io.euhedral_execution.training.learning.data.PolicyPredictionCurve;
import io.euhedral_execution.training.merge.PolicyComparator;
import io.euhedral_execution.training.merge.data.CalibrationPlan;
import io.euhedral_execution.training.merge.data.MergeRecords.RobustPolicySummary;
import io.euhedral_execution.training.optimization.PolicyCurvePredictor;
import io.euhedral_execution.training.optimization.PredictedPolicyComparator;
import io.euhedral_execution.training.optimization.PredictedPolicyRanker;
import io.euhedral_execution.training.optimization.SchedulerSeeds;
import io.euhedral_execution.training.optimization.data.CandidateGenerationResult;
import io.euhedral_execution.training.optimization.data.PredictedPolicySummary;
import io.euhedral_execution.training.optimization.data.ScheduledPolicyPrediction;
import io.euhedral_execution.training.optimization.enums.CandidateOrigin;
import io.euhedral_execution.training.optimization.enums.SchedulePolicyOrigin;
import io.euhedral_execution.training.scheduling.config.CandidateBudgetConfig;
import io.euhedral_execution.training.scheduling.data.BudgetAllocation;
import io.euhedral_execution.training.scheduling.data.CarryForwardEntry;
import io.euhedral_execution.training.scheduling.data.CarryScenarioState;
import io.euhedral_execution.training.scheduling.data.IterationSchedule;
import io.euhedral_execution.training.scheduling.data.OptimizationCorpusView;
import io.euhedral_execution.training.scheduling.data.ScenarioBudgetReport;
import io.euhedral_execution.training.scheduling.data.SchedulePreparation;
import io.euhedral_execution.training.scheduling.data.ScheduledRun;
import io.euhedral_execution.training.scheduling.enums.RunKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class CandidateScheduler {
    public static SchedulePreparation prepare(int iteration, int candidateBudget,
            CalibrationPlan calibrationPlan, OptimizationCorpusView corpus,
            List<CarryForwardEntry> rescoredQueue, List<SourceScenario> selectedScenarios,
            CandidateBudgetConfig budgetConfig, PolicyCurvePredictor predictor) {
        BudgetAllocation allocation = BudgetAllocator.allocate(candidateBudget,
                calibrationPlan.anchors().fixedAnchors().size(), budgetConfig);
        List<PolicyVector> anchors = calibrationPlan.anchors().fixedAnchors();
        Set<PolicyId> anchorIds = anchors.stream().map(PolicyVector::id)
                .collect(java.util.stream.Collectors.toSet());
        List<RobustPolicySummary> leaders = corpus.eligiblePolicies().stream()
                .filter(RobustPolicySummary::eligible)
                .filter(summary -> !anchorIds.contains(summary.policy().id()))
                .sorted(PolicyComparator.BEST_FIRST)
                .limit(allocation.leaderRevalidation())
                .toList();
        TreeMap<SourceScenario, List<CarryForwardEntry>> carry = new TreeMap<>();
        CarryForwardQueue queue = new CarryForwardQueue(rescoredQueue);
        for (SourceScenario scenario : selectedScenarios) {
            carry.put(scenario, queue.selectFor(scenario, iteration, allocation.carryForward()));
        }
        List<PredictedPolicySummary> leaderPredictions = predictor.predict(
                leaders.stream().map(RobustPolicySummary::policy).toList());
        ArrayList<ScheduledPolicyPrediction> predictions = new ArrayList<>();
        leaderPredictions.forEach(summary -> predictions.add(new ScheduledPolicyPrediction(
                summary.policy(), summary, SchedulePolicyOrigin.MEASURED_LEADER)));
        TreeMap<PolicyId, CarryForwardEntry> uniqueCarry = new TreeMap<>();
        carry.values().forEach(rows -> rows.forEach(entry ->
                uniqueCarry.put(entry.policy().id(), entry)));
        for (CarryForwardEntry entry : uniqueCarry.values()) {
            var curve = new PolicyPredictionCurve(
                    entry.policy(), entry.scenarios().values().stream()
                    .map(CarryScenarioState::prediction).toList());
            predictions.add(new ScheduledPolicyPrediction(entry.policy(),
                    PredictedPolicyRanker.summarize(curve,
                            new TreeSet<>(entry.scenarios().keySet())),
                    SchedulePolicyOrigin.MEASURED_CARRY));
        }
        int maximumKnownShortfall = selectedScenarios.stream().mapToInt(scenario ->
                allocation.carryForward() - carry.get(scenario).size()
                        + allocation.leaderRevalidation() - leaders.size()).max().orElse(0);
        return new SchedulePreparation(iteration, candidateBudget, allocation, selectedScenarios,
                anchors, carry, leaders, predictions, allocation.exploration(),
                maximumKnownShortfall,
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
                        scheduleOrigin(candidate.origin()))));
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
            List<RolePolicy> rolePolicies = policiesForScenario(preparation, generated, scenario);
            String cohort = SchedulerSeeds.candidateCohortId(trainingRunId, RunKind.NORMAL.name(),
                    preparation.iteration(), scenario, rolePolicies, schedulerSeed);
            BenchmarkParameters identityParameters = new BenchmarkParameters(
                    benchmarkConfig.expectedRepetitions(), benchmarkConfig.sampleDurationNanos(),
                    benchmarkConfig.livenessTimeoutNanos(), benchmarkConfig.framesPerSource(),
                    benchmarkConfig.resetTimeoutNanos(), benchmarkConfig.orderedFrames(), cpuSetHex,
                    java.util.stream.IntStream.range(0, scenario.sourceCount())
                            .mapToObj(index -> new FrameSourceSeed(index, 0, 0)).toList());
            String runId = SchedulerSeeds.benchmarkRunId(trainingRunId, RunKind.NORMAL.name(),
                    preparation.iteration(), scenario, cohort, identityParameters, commitSha,
                    dirtyWorkingTree, schedulerSeed);
            List<FrameSourceSeed> seeds = java.util.stream.IntStream.range(0,
                    scenario.sourceCount()).mapToObj(index ->
                    SchedulerSeeds.frameSourceSeed(runId, index, schedulerSeed)).toList();
            BenchmarkParameters parameters = new BenchmarkParameters(
                    benchmarkConfig.expectedRepetitions(), benchmarkConfig.sampleDurationNanos(),
                    benchmarkConfig.livenessTimeoutNanos(), benchmarkConfig.framesPerSource(),
                    benchmarkConfig.resetTimeoutNanos(), benchmarkConfig.orderedFrames(), cpuSetHex,
                    seeds);
            List<ScheduledPolicy> policies = orderPolicies(rolePolicies, preparation.fixedAnchors(),
                    cohort, schedulerSeed, preparation.candidateBudget());
            runs.add(new ScheduledRun(RunKind.NORMAL, scenario, runId, cohort, parameters,
                    policies));
            BudgetAllocation a = preparation.requestedAllocation();
            int carryAssigned = count(policies, PolicyRole.CARRY_FORWARD);
            int leaderAssigned = count(policies, PolicyRole.LEADER_REVALIDATION);
            int auditAssigned = count(policies, PolicyRole.DISAGREEMENT_AUDIT);
            int explorationAssigned = count(policies, PolicyRole.EXPLORATION);
            reports.add(new ScenarioBudgetReport(scenario, preparation.candidateBudget(),
                    a.fixedAnchors(), a.fixedAnchors(), a.carryForward(),
                    carryAssigned, a.leaderRevalidation(), leaderAssigned,
                    a.disagreementAudit(), auditAssigned, a.exploration(), explorationAssigned,
                    a.carryForward() - carryAssigned,
                    a.leaderRevalidation() - leaderAssigned,
                    a.disagreementAudit() - auditAssigned, policies.size()));
        }
        return new IterationSchedule(trainingRunId, preparation.iteration(), runs, predictions,
                admissions, reports, generated.nextSobolCursor());
    }

    private static List<RolePolicy> policiesForScenario(SchedulePreparation preparation,
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
        int carryShortfall = preparation.requestedAllocation().carryForward()
                - preparation.carryByScenario().getOrDefault(scenario, List.of()).size();
        int leaderShortfall = preparation.requestedAllocation().leaderRevalidation()
                - preparation.leaders().size();
        int overflowNeeded = carryShortfall + leaderShortfall + generated.auditShortfall();
        generated.overflowExploration().stream().limit(overflowNeeded).forEach(candidate ->
                rows.add(new RolePolicy(candidate.policy(), PolicyRole.EXPLORATION)));
        LinkedHashMap<PolicyId, RolePolicy> unique = new LinkedHashMap<>();
        rows.forEach(row -> {
            if (unique.putIfAbsent(row.policy().id(), row) != null) {
                throw new IllegalArgumentException("Duplicate scheduled policy role");
            }
        });
        if (unique.size() != preparation.candidateBudget()) {
            throw new IllegalStateException("Schedule does not fill policy budget");
        }
        return List.copyOf(unique.values());
    }

    private static List<ScheduledPolicy> orderPolicies(List<RolePolicy> policies,
            List<PolicyVector> anchors, String cohort, long schedulerSeed, int budget) {
        Set<PolicyId> anchorIds = anchors.stream().map(PolicyVector::id)
                .collect(java.util.stream.Collectors.toSet());
        List<RolePolicy> nonAnchors = policies.stream()
                .filter(row -> !anchorIds.contains(row.policy().id()))
                .sorted((left, right) -> {
                    int result = Long.compareUnsigned(
                            SchedulerSeeds.trialOrderKey(cohort, left.policy().id(),
                                    schedulerSeed),
                            SchedulerSeeds.trialOrderKey(cohort, right.policy().id(),
                                    schedulerSeed));
                    return result != 0 ? result : left.policyId().compareTo(right.policyId());
                })
                .toList();
        RolePolicy[] ordered = new RolePolicy[budget];
        List<RolePolicy> sortedAnchors = policies.stream()
                .filter(row -> anchorIds.contains(row.policy().id()))
                .sorted(Comparator.comparing(RolePolicy::policyId)).toList();
        for (int i = 0; i < sortedAnchors.size(); i++) {
            int position = Math.toIntExact(Math.floorDiv(
                    Math.multiplyExact(2L * i + 1L, budget), 2L * sortedAnchors.size()));
            ordered[position] = sortedAnchors.get(i);
        }
        Iterator<RolePolicy> iterator = nonAnchors.iterator();
        for (int i = 0; i < ordered.length; i++) {
            if (ordered[i] == null) {
                ordered[i] = iterator.next();
            }
        }
        ArrayList<ScheduledPolicy> result = new ArrayList<>(budget);
        for (int i = 0; i < budget; i++) {
            result.add(new ScheduledPolicy(i + 1, ordered[i].policy(),
                    Set.of(ordered[i].role())));
        }
        return List.copyOf(result);
    }

    private static int count(List<ScheduledPolicy> policies, PolicyRole role) {
        return Math.toIntExact(policies.stream().filter(policy ->
                policy.roles().contains(role)).count());
    }

    private record RolePolicy(PolicyVector policy, PolicyRole role)
            implements SchedulerSeeds.PolicyWithRole {
        @Override
        public PolicyId policyId() {
            return policy.id();
        }
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
