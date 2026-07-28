package io.euhedral_execution.training.scheduling;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.optimization.ScheduledPolicyPrediction;
import java.util.List;

public record IterationSchedule(int iteration, List<ScheduledRun> runs,
        List<ScheduledPolicyPrediction> selectedPredictions, List<PolicyId> carryAdmissions,
        List<ScenarioBudgetReport> budgetReports, long nextSobolCursor) {
    public IterationSchedule {
        runs = List.copyOf(runs);
        selectedPredictions = List.copyOf(selectedPredictions);
        carryAdmissions = List.copyOf(carryAdmissions);
        budgetReports = List.copyOf(budgetReports);
    }
}
