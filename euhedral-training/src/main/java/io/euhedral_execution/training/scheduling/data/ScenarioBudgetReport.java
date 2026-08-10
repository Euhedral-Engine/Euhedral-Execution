package io.euhedral_execution.training.scheduling.data;

import io.euhedral_execution.training.data.SourceScenario;

public record ScenarioBudgetReport(
        SourceScenario scenario,
        int candidateBudget,
        int fixedRequested,
        int fixedAssigned,
        int carryRequested,
        int carryAssigned,
        int leaderRequested,
        int leaderAssigned,
        int auditRequested,
        int auditAssigned,
        int explorationRequested,
        int explorationAssigned,
        int carryTransferredToExploration,
        int leaderTransferredToExploration,
        int auditTransferredToExploration,
        int totalAssigned) {}
