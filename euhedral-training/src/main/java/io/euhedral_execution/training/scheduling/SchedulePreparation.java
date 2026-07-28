package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.merge.CalibrationPlan;
import io.euhedral_execution.training.merge.MergeRecords.RobustPolicySummary;
import io.euhedral_execution.training.optimization.ScheduledPolicyPrediction;
import java.util.List;

public record SchedulePreparation(int iteration, int candidateBudget,
        BudgetAllocation requestedAllocation, List<SourceScenario> scenarios,
        List<PolicyVector> fixedAnchors,
        java.util.SortedMap<SourceScenario, List<CarryForwardEntry>> carryByScenario,
        List<RobustPolicySummary> leaders, List<ScheduledPolicyPrediction> measuredPredictions,
        int baseExplorationCount, int preAuditOverflowCount, int disagreementAuditCount) {
    public SchedulePreparation {
        scenarios = List.copyOf(scenarios);
        fixedAnchors = List.copyOf(fixedAnchors);
        leaders = List.copyOf(leaders);
        measuredPredictions = List.copyOf(measuredPredictions);
    }
}
