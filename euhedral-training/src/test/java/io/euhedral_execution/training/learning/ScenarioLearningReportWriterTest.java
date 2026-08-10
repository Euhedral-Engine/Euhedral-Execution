package io.euhedral_execution.training.learning;

import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.scenarios;
import static org.assertj.core.api.Assertions.assertThat;

import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.enums.EvaluationStatus;
import io.euhedral_execution.training.learning.enums.ScenarioFeatureSet;
import io.euhedral_execution.training.learning.output.EvaluationSummary;
import io.euhedral_execution.training.learning.output.ScenarioLearningReportWriter;
import io.euhedral_execution.training.learning.output.TrainingHistoryEntry;
import io.euhedral_execution.training.learning.statistics.AblationMetric;
import io.euhedral_execution.training.learning.statistics.LosoEvaluationMetrics;
import io.euhedral_execution.training.learning.statistics.ScenarioEvaluationMetrics;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioLearningReportWriterTest {
    @TempDir
    Path temporary;

    private static ScenarioEvaluationMetrics metric(SourceScenario scenario, String fold) {
        return new ScenarioEvaluationMetrics(
                "GROUPED_TEST",
                fold,
                ScenarioFeatureSet.RATIO_ONLY,
                scenario,
                10,
                10,
                0.1,
                0.1,
                0,
                OptionalDouble.of(0.8),
                1,
                1,
                OptionalDouble.of(1),
                OptionalDouble.of(1),
                0.2,
                1,
                0.01,
                0.02,
                EvaluationStatus.OK);
    }

    private static AblationMetric ablation(String fold, ScenarioFeatureSet feature) {
        return new AblationMetric(
                "VALIDATION_CONTEXT_LOSO",
                fold,
                feature,
                feature == ScenarioFeatureSet.POLICY_ONLY
                        ? ScenarioFeatureSet.RATIO_ONLY
                        : ScenarioFeatureSet.POLICY_ONLY,
                "scenario",
                10,
                OptionalDouble.of(0.1),
                OptionalDouble.of(0.8),
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                false,
                "OK",
                "reason,with,commas");
    }

    @Test
    void reportBytesAreStableUnderLogicalInputPermutation() throws Exception {
        List<SourceScenario> scenarios = new ArrayList<>(scenarios());
        ScenarioEvaluationMetrics first = metric(scenarios.get(0), "fold-b");
        ScenarioEvaluationMetrics second = metric(scenarios.get(1), "fold-a");
        EvaluationSummary summary = new EvaluationSummary(
                "GROUPED_TEST",
                ScenarioFeatureSet.RATIO_ONLY,
                List.of(second, first),
                OptionalDouble.of(0.1),
                OptionalDouble.of(0.1),
                OptionalDouble.of(0.8),
                OptionalDouble.of(1),
                OptionalDouble.of(1),
                OptionalDouble.of(0.1),
                OptionalDouble.of(0.1));
        Path grouped = temporary.resolve("grouped.csv");
        ScenarioLearningReportWriter.writeGrouped(grouped, summary);
        String firstBytes = Files.readString(grouped);
        ScenarioLearningReportWriter.writeGrouped(
                grouped,
                new EvaluationSummary(
                        "GROUPED_TEST",
                        ScenarioFeatureSet.RATIO_ONLY,
                        List.of(first, second),
                        summary.macroMae(),
                        summary.macroRmse(),
                        summary.macroSpearman(),
                        summary.macroPrecisionAtTen(),
                        summary.macroRecallAtTen(),
                        summary.worstScenarioMae(),
                        summary.microMae()));
        assertThat(Files.readString(grouped))
                .isEqualTo(firstBytes)
                .startsWith(ScenarioLearningReportWriter.GROUPED_HEADER)
                .endsWith("\n");

        AblationMetric a = ablation("z", ScenarioFeatureSet.RATIO_ONLY);
        AblationMetric b = ablation("a", ScenarioFeatureSet.POLICY_ONLY);
        Path ablation = temporary.resolve("ablation.csv");
        ScenarioLearningReportWriter.writeAblation(ablation, List.of(a, b));
        String ablationBytes = Files.readString(ablation);
        ScenarioLearningReportWriter.writeAblation(ablation, List.of(b, a));
        assertThat(Files.readString(ablation))
                .isEqualTo(ablationBytes)
                .startsWith(ScenarioLearningReportWriter.ABLATION_HEADER)
                .endsWith("\n");

        LosoEvaluationMetrics firstLoso =
                new LosoEvaluationMetrics(first, first.scenario().ratio().asDouble(), true, 3, 3);
        LosoEvaluationMetrics secondLoso =
                new LosoEvaluationMetrics(second, second.scenario().ratio().asDouble(), false, 3, 3);
        Path loso = temporary.resolve("loso.csv");
        ScenarioLearningReportWriter.writeLoso(loso, List.of(secondLoso, firstLoso));
        String losoBytes = Files.readString(loso);
        ScenarioLearningReportWriter.writeLoso(loso, List.of(firstLoso, secondLoso));
        assertThat(Files.readString(loso))
                .isEqualTo(losoBytes)
                .startsWith(ScenarioLearningReportWriter.LOSO_HEADER)
                .endsWith("\n");

        TrainingHistoryEntry firstHistory = new TrainingHistoryEntry(
                "PRODUCTION", "all", ScenarioFeatureSet.RATIO_ONLY, 1, 2, 1, 0.1, OptionalDouble.of(0.8), 0.2, true);
        TrainingHistoryEntry secondHistory = new TrainingHistoryEntry(
                "PRODUCTION", "all", ScenarioFeatureSet.RATIO_ONLY, 0, 1, 0, 0.2, OptionalDouble.empty(), 0.3, true);
        Path history = temporary.resolve("history.csv");
        ScenarioLearningReportWriter.writeHistory(history, List.of(firstHistory, secondHistory));
        String historyBytes = Files.readString(history);
        ScenarioLearningReportWriter.writeHistory(history, List.of(secondHistory, firstHistory));
        assertThat(Files.readString(history))
                .isEqualTo(historyBytes)
                .startsWith(ScenarioLearningReportWriter.HISTORY_HEADER)
                .endsWith("\n");
    }
}
