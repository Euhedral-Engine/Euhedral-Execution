package io.euhedral_execution.training.learning.metadata;

import java.util.Objects;
import java.util.OptionalDouble;

public record EvaluationSummaryMetadata(String groupedReport, String losoReport,
                                        OptionalDouble groupedMacroMae,
                                        OptionalDouble groupedMacroSpearman,
                                        OptionalDouble groupedMacroPrecisionAtTen,
                                        OptionalDouble losoMacroMae,
                                        OptionalDouble losoMacroSpearman,
                                        OptionalDouble losoWorstScenarioMae) {

    private static void validate(OptionalDouble value, double minimum, double maximum) {
        if (value.isPresent() && (!Double.isFinite(value.getAsDouble())
                || value.getAsDouble() < minimum || value.getAsDouble() > maximum)) {
            throw new IllegalArgumentException("Non-finite evaluation summary");
        }
    }

    public EvaluationSummaryMetadata {
        Objects.requireNonNull(groupedReport);
        Objects.requireNonNull(losoReport);
        Objects.requireNonNull(groupedMacroMae);
        Objects.requireNonNull(groupedMacroSpearman);
        Objects.requireNonNull(groupedMacroPrecisionAtTen);
        Objects.requireNonNull(losoMacroMae);
        Objects.requireNonNull(losoMacroSpearman);
        Objects.requireNonNull(losoWorstScenarioMae);
        if (!groupedReport.equals("grouped-evaluation.csv") || !losoReport.equals(
                "loso-evaluation.csv")) {
            throw new IllegalArgumentException("Unexpected evaluation report name");
        }
        validate(groupedMacroMae, 0, 1);
        validate(groupedMacroSpearman, -1, 1);
        validate(groupedMacroPrecisionAtTen, 0, 1);
        validate(losoMacroMae, 0, 1);
        validate(losoMacroSpearman, -1, 1);
        validate(losoWorstScenarioMae, 0, 1);
    }
}
