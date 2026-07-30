package io.euhedral_execution.training.scheduling.data;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.optimization.data.ScheduledPolicyPrediction;
import java.util.List;

public record IterationSchedule(String trainingRunId, int iteration, List<ScheduledRun> runs,
        List<ScheduledPolicyPrediction> selectedPredictions, List<PolicyId> carryAdmissions,
        List<ScenarioBudgetReport> budgetReports, long nextSobolCursor) {
    public IterationSchedule {
        if (trainingRunId == null
                || !trainingRunId.matches("[a-z0-9][a-z0-9._-]{0,95}")
                || iteration < 0 || nextSobolCursor < 0) {
            throw new IllegalArgumentException("Invalid iteration schedule identity");
        }
        runs = List.copyOf(runs);
        selectedPredictions = List.copyOf(selectedPredictions);
        carryAdmissions = List.copyOf(carryAdmissions);
        budgetReports = List.copyOf(budgetReports);
    }
}
