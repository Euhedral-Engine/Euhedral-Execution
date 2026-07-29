package io.euhedral_execution.training.learning.inputs;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;

public record ScenarioLearningTable(List<ScenarioLearningRow> rows,
                                    SortedMap<PolicyId, PolicyVector> policies,
                                    SortedSet<SourceScenario> requiredScenarios,
                                    ScenarioDatasetAudit audit, String datasetFingerprintSha256) {

    public ScenarioLearningTable {
        rows = List.copyOf(rows);
        policies = Collections.unmodifiableSortedMap(new TreeMap<>(policies));
        requiredScenarios = Collections.unmodifiableSortedSet(new TreeSet<>(requiredScenarios));
        Objects.requireNonNull(audit);
        if (requiredScenarios.isEmpty() || !datasetFingerprintSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid learning table");
        }
        if (audit.policyCount() != policies.size()
                || audit.requiredScenarioCount() != requiredScenarios.size()
                || audit.includedStrongRowCount() + audit.includedWeakRowCount() != rows.size()
                || audit.rowCount() != audit.includedStrongRowCount() + audit.includedWeakRowCount()
                + audit.weakExcludedRowCount() + audit.missingRowCount()
                + audit.noValidRunRowCount() + audit.noAcceptedCalibrationRowCount()
                + audit.nonRequiredRowCount()) {
            throw new IllegalArgumentException("Learning-table audit counts disagree");
        }
        TreeSet<SourceScenario> represented = new TreeSet<>();
        for (ScenarioLearningRow row : rows) {
            PolicyVector registered = policies.get(row.policy().id());
            if (registered == null || !registered.bitwiseEquals(row.policy())
                    || !requiredScenarios.contains(row.scenario())) {
                throw new IllegalArgumentException("Learning row is outside the table registry");
            }
            represented.add(row.scenario());
        }
        if (!represented.equals(requiredScenarios)) {
            throw new IllegalArgumentException("Required scenario has no included learning row");
        }
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i - 1).compareTo(rows.get(i)) >= 0) {
                throw new IllegalArgumentException("Rows must be unique and sorted");
            }
        }
    }
}
