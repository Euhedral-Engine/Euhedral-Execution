package io.euhedral_execution.training.data;

import java.util.Objects;

public record ObservationKey(
        String benchmarkRunId,
        SourceScenario scenario,
        PolicyId policyId,
        int repetitionNumber) implements Comparable<ObservationKey> {

    public ObservationKey {
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(policyId);
        if (benchmarkRunId == null
                || !benchmarkRunId.matches("[a-z0-9][a-z0-9._-]{0,95}")
                || repetitionNumber < 1 || repetitionNumber > 999_999) {
            throw new IllegalArgumentException("Invalid observation key");
        }
    }

    public String canonical() {
        return "ob1/" + benchmarkRunId + "/" + scenario.canonical() + "/"
                + policyId.canonical() + "/rep-" + String.format("%06d", repetitionNumber);
    }

    @Override
    public int compareTo(ObservationKey other) {
        int result = benchmarkRunId.compareTo(other.benchmarkRunId);
        if (result == 0) {
            result = scenario.compareTo(other.scenario);
        }
        if (result == 0) {
            result = policyId.compareTo(other.policyId);
        }
        return result != 0 ? result : Integer.compare(repetitionNumber, other.repetitionNumber);
    }
}
