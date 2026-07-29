package io.euhedral_execution.training.learning;

import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.policies;
import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.result;
import static io.euhedral_execution.training.learning.fixtures.ScenarioLearningFixtures.scenarios;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.inputs.ScenarioInputs;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningReader;
import io.euhedral_execution.training.learning.inputs.ScenarioLearningTable;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResult;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResultStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalDouble;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioLearningReaderTest {

    private static void assertRejected(Fixture fixture) {
        assertThatThrownBy(() -> ScenarioLearningReader.read(
                fixture.inputs(), fixture.required(), false))
                .isInstanceOf(IOException.class);
    }

    private static void writeLines(Path path, List<String> lines) throws IOException {
        Files.writeString(path, String.join("\n", lines) + "\n");
    }

    private static void writeLeaders(Path path, List<PolicyVector> policies) throws IOException {
        StringBuilder output = new StringBuilder(vectorHeader(
                "schema_version,robust_rank,policy_id"));
        for (int index = 0; index < policies.size(); index++) {
            output.append("1,").append(index + 1).append(',')
                    .append(policies.get(index).id().canonical());
            appendWeights(output, policies.get(index));
            output.append('\n');
        }
        Files.writeString(path, output);
    }

    private static void writeIncomplete(Path path, List<IncompleteVector> policies)
            throws IOException {
        StringBuilder output = new StringBuilder(vectorHeader(
                "schema_version,valid_required_scenario_count,"
                        + "observed_required_scenario_count,policy_id"));
        for (IncompleteVector entry : policies) {
            output.append("1,").append(entry.valid()).append(',')
                    .append(entry.observed()).append(',')
                    .append(entry.policy().id().canonical());
            appendWeights(output, entry.policy());
            output.append('\n');
        }
        Files.writeString(path, output);
    }

    private static ScenarioResult invalidResult(SourceScenario scenario, PolicyVector policy,
            ScenarioResultStatus status) {
        boolean observed = status != ScenarioResultStatus.MISSING;
        int count = observed ? 1 : 0;
        return new ScenarioResult(scenario, policy, status, count, 0, 0,
                status == ScenarioResultStatus.NO_ACCEPTED_CALIBRATION ? count : 0,
                0, count, OptionalDouble.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty(),
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalDouble.empty());
    }

    private static String vectorHeader(String prefix) {
        StringBuilder output = new StringBuilder(prefix);
        for (int index = 0; index < 28; index++) {
            output.append(",weight_%02d_bits".formatted(index));
        }
        return output.append('\n').toString();
    }

    private static void appendWeights(StringBuilder output, PolicyVector policy) {
        for (double weight : policy.copyWeights()) {
            output.append(',').append("%016x".formatted(Double.doubleToRawLongBits(weight)));
        }
    }

    private static void writeScenarios(Path path, List<ScenarioResult> rows) throws IOException {
        String header = "schema_version,scenario_id,environment_id,source_count,"
                + "available_physical_core_count,source_ratio_numerator,"
                + "source_ratio_denominator,policy_id,status,total_run_count,"
                + "accepted_run_count,weak_run_count,uncalibrated_run_count,"
                + "successful_repetition_count,planned_repetition_count,throughput_p25,"
                + "throughput_median,throughput_p75,throughput_iqr,"
                + "median_within_run_relative_iqr,mean_timeout_rate,mean_failure_rate,"
                + "mean_non_success_rate,bootstrap_median_ci_low,bootstrap_median_ci_high,"
                + "quality\n";
        StringBuilder output = new StringBuilder(header);
        for (ScenarioResult row : rows) {
            output.append(String.join(",", "1", row.scenario().canonical(),
                    row.scenario().environmentId(), Integer.toString(row.scenario().sourceCount()),
                    Integer.toString(row.scenario().availablePhysicalCoreCount()),
                    Integer.toString(row.scenario().ratio().numerator()),
                    Integer.toString(row.scenario().ratio().denominator()),
                    row.policy().id().canonical(), row.status().name(),
                    Integer.toString(row.totalRunCount()), Integer.toString(row.acceptedRunCount()),
                    Integer.toString(row.weakRunCount()),
                    Integer.toString(row.uncalibratedRunCount()),
                    Integer.toString(row.successfulRepetitionCount()),
                    Integer.toString(row.plannedRepetitionCount()), optional(row.throughputP25()),
                    optional(row.throughputMedian()), optional(row.throughputP75()),
                    optional(row.throughputIqr()), optional(row.medianWithinRunRelativeIqr()),
                    optional(row.meanTimeoutRate()), optional(row.meanFailureRate()),
                    optional(row.meanNonSuccessRate()), optional(row.bootstrapMedianCiLow()),
                    optional(row.bootstrapMedianCiHigh()), optional(row.quality()))).append('\n');
        }
        Files.writeString(path, output);
    }

    private static String optional(OptionalDouble value) {
        return value.isPresent() ? Double.toString(value.getAsDouble()) : "";
    }
    @TempDir
    Path temporary;

    @Test
    void joinsBothDictionariesInNativePhase1OrderAndSortsLearningRows() throws Exception {
        Fixture fixture = fixture();
        ScenarioLearningTable table = ScenarioLearningReader.read(
                fixture.inputs(), fixture.required(), false);
        assertThat(table.policies()).hasSize(4);
        assertThat(table.rows()).isSorted();
        assertThat(table.rows()).allMatch(row ->
                row.sourceStatus() == ScenarioResultStatus.VALID_STRONG);
        assertThat(table.rows()).hasSize(8);
        assertThat(table.audit().weakExcludedRowCount()).isOne();
        assertThat(table.audit().missingRowCount()).isOne();
        assertThat(table.audit().noValidRunRowCount()).isOne();
        assertThat(table.audit().noAcceptedCalibrationRowCount()).isOne();
        assertThat(table.audit().nonRequiredRowCount()).isOne();
        assertThat(table.rows().stream().map(row -> row.policy().id()).distinct()).hasSize(4);
        for (PolicyVector policy : fixture.policies()) {
            assertThat(table.policies().get(policy.id()).copyWeights())
                    .containsExactly(policy.copyWeights());
        }
    }

    @Test
    void weakRowsAreAnExplicitAblation() throws Exception {
        Fixture fixture = fixture();
        ScenarioLearningTable defaultTable = ScenarioLearningReader.read(
                fixture.inputs(), fixture.required(), false);
        ScenarioLearningTable weakTable = ScenarioLearningReader.read(
                fixture.inputs(), fixture.required(), true);
        assertThat(weakTable.rows()).hasSize(defaultTable.rows().size() + 1);
        assertThat(weakTable.audit().includedWeakRowCount()).isOne();
        assertThat(defaultTable.audit().weakExcludedRowCount()).isOne();
    }

    @Test
    void scenarioRowPermutationDoesNotChangeTableOrFingerprint() throws Exception {
        Fixture fixture = fixture();
        ScenarioLearningTable first = ScenarioLearningReader.read(
                fixture.inputs(), fixture.required(), false);
        List<String> lines = Files.readAllLines(fixture.inputs().scenarioResults());
        String header = lines.removeFirst();
        Collections.shuffle(lines, new java.util.Random(17));
        Files.writeString(fixture.inputs().scenarioResults(),
                header + "\n" + String.join("\n", lines) + "\n");
        ScenarioLearningTable second = ScenarioLearningReader.read(
                fixture.inputs(), fixture.required(), false);
        assertThat(second.rows()).containsExactlyElementsOf(first.rows());
        assertThat(second.datasetFingerprintSha256())
                .isEqualTo(first.datasetFingerprintSha256());
    }

    @Test
    void inMemoryAndPersistedPathsConverge() throws Exception {
        Fixture fixture = fixture();
        ScenarioLearningTable persisted = ScenarioLearningReader.read(
                fixture.inputs(), fixture.required(), true);
        ScenarioLearningTable inMemory = ScenarioLearningReader.fromScenarioResults(
                fixture.results(), fixture.required(), true);
        assertThat(inMemory.rows()).containsExactlyElementsOf(persisted.rows());
        assertThat(inMemory.datasetFingerprintSha256())
                .isEqualTo(persisted.datasetFingerprintSha256());
    }

    @Test
    void rejectsChangedHeadersDuplicateRowsAndLegacyText() throws Exception {
        Fixture fixture = fixture();
        Path scenarios = fixture.inputs().scenarioResults();
        String original = Files.readString(scenarios);
        Files.writeString(scenarios, original.replaceFirst("schema_version", "schema"));
        assertThatThrownBy(() -> ScenarioLearningReader.read(
                fixture.inputs(), fixture.required(), false)).isInstanceOf(IOException.class);
        Files.writeString(scenarios, original + original.lines().skip(1).findFirst().orElseThrow()
                + "\n");
        assertThatThrownBy(() -> ScenarioLearningReader.read(
                fixture.inputs(), fixture.required(), false)).isInstanceOf(IOException.class);
        Files.writeString(fixture.inputs().robustLeaderVectors(), "1 2 3\n4 5 6\n");
        assertThatThrownBy(() -> ScenarioLearningReader.read(
                fixture.inputs(), fixture.required(), false)).isInstanceOf(IOException.class)
                .hasMessageContaining("header");
    }

    @Test
    void rejectsMissingDuplicateChangedAndMisorderedVectors() throws Exception {
        Fixture missing = fixture();
        List<String> leaderLines = Files.readAllLines(
                missing.inputs().robustLeaderVectors());
        leaderLines.remove(1);
        writeLines(missing.inputs().robustLeaderVectors(), leaderLines);
        assertRejected(missing);

        Fixture changed = fixture();
        leaderLines = Files.readAllLines(changed.inputs().robustLeaderVectors());
        String[] changedFields = leaderLines.get(1).split(",", -1);
        changedFields[3] = changedFields[3].equals("0000000000000000")
                ? "3ff0000000000000" : "0000000000000000";
        leaderLines.set(1, String.join(",", changedFields));
        writeLines(changed.inputs().robustLeaderVectors(), leaderLines);
        assertRejected(changed);

        Fixture duplicated = fixture();
        writeIncomplete(duplicated.inputs().incompletePolicyVectors(), List.of(
                new IncompleteVector(duplicated.policies().get(2), 2, 3),
                new IncompleteVector(duplicated.policies().get(3), 1, 2),
                new IncompleteVector(duplicated.policies().get(0), 0, 0)));
        assertRejected(duplicated);

        Fixture wrongRank = fixture();
        leaderLines = Files.readAllLines(wrongRank.inputs().robustLeaderVectors());
        leaderLines.set(1, leaderLines.get(1).replaceFirst("^1,1,", "1,2,"));
        writeLines(wrongRank.inputs().robustLeaderVectors(), leaderLines);
        assertRejected(wrongRank);

        Fixture wrongCounts = fixture();
        writeIncomplete(wrongCounts.inputs().incompletePolicyVectors(), List.of(
                new IncompleteVector(wrongCounts.policies().get(2), 1, 3),
                new IncompleteVector(wrongCounts.policies().get(3), 1, 2)));
        assertRejected(wrongCounts);

        Fixture wrongOrder = fixture();
        writeIncomplete(wrongOrder.inputs().incompletePolicyVectors(), List.of(
                new IncompleteVector(wrongOrder.policies().get(3), 1, 2),
                new IncompleteVector(wrongOrder.policies().get(2), 2, 3)));
        assertRejected(wrongOrder);
    }

    @Test
    void rejectsMissingOrCorruptScenarioRows() throws Exception {
        Fixture missing = fixture();
        List<String> lines = Files.readAllLines(missing.inputs().scenarioResults());
        lines.remove(1);
        writeLines(missing.inputs().scenarioResults(), lines);
        assertRejected(missing);

        Fixture wrongScenario = fixture();
        lines = Files.readAllLines(wrongScenario.inputs().scenarioResults());
        String[] fields = lines.get(1).split(",", -1);
        fields[1] = fields[1].replaceFirst("^s1-", "s1-other-");
        lines.set(1, String.join(",", fields));
        writeLines(wrongScenario.inputs().scenarioResults(), lines);
        assertRejected(wrongScenario);

        Fixture mixedSchema = fixture();
        lines = Files.readAllLines(mixedSchema.inputs().scenarioResults());
        lines.set(1, lines.get(1).replaceFirst("^1,", "2,"));
        writeLines(mixedSchema.inputs().scenarioResults(), lines);
        assertRejected(mixedSchema);

        Fixture nonFinite = fixture();
        lines = Files.readAllLines(nonFinite.inputs().scenarioResults());
        fields = lines.get(1).split(",", -1);
        fields[25] = "NaN";
        lines.set(1, String.join(",", fields));
        writeLines(nonFinite.inputs().scenarioResults(), lines);
        assertRejected(nonFinite);
    }

    private Fixture fixture() throws IOException {
        List<PolicyVector> policies = policies(4);
        List<SourceScenario> scenarios = new ArrayList<>(scenarios());
        TreeSet<SourceScenario> required = new TreeSet<>(scenarios.subList(0, 3));
        ArrayList<ScenarioResult> results = new ArrayList<>();
        for (int policyIndex = 0; policyIndex < policies.size(); policyIndex++) {
            for (int scenarioIndex = 0; scenarioIndex < required.size(); scenarioIndex++) {
                ScenarioResultStatus status = ScenarioResultStatus.VALID_STRONG;
                if (policyIndex == 2 && scenarioIndex == 0) {
                    status = ScenarioResultStatus.VALID_WEAK_OVERRIDE;
                } else if (policyIndex == 2 && scenarioIndex == 2) {
                    status = ScenarioResultStatus.NO_VALID_RUN;
                } else if (policyIndex == 3 && scenarioIndex == 1) {
                    status = ScenarioResultStatus.NO_ACCEPTED_CALIBRATION;
                } else if (policyIndex == 3 && scenarioIndex == 2) {
                    status = ScenarioResultStatus.MISSING;
                }
                double quality = (policyIndex + scenarioIndex) / 4.0;
                SourceScenario scenario = new ArrayList<>(required).get(scenarioIndex);
                boolean valid = status == ScenarioResultStatus.VALID_STRONG
                        || status == ScenarioResultStatus.VALID_WEAK_OVERRIDE;
                results.add(valid
                        ? result(scenario, policies.get(policyIndex), status,
                        100 + policyIndex, OptionalDouble.of(quality))
                        : invalidResult(scenario, policies.get(policyIndex), status));
            }
        }
        results.add(result(scenarios.get(3), policies.getFirst(),
                ScenarioResultStatus.VALID_STRONG, 101, OptionalDouble.of(0.5)));
        Path scenarioFile = temporary.resolve("scenario-results.csv");
        Path leaders = temporary.resolve("robust-leaders.vectors.csv");
        Path incomplete = temporary.resolve("incomplete-policies.vectors.csv");
        writeScenarios(scenarioFile, results);
        // Rank order intentionally differs from unsigned PolicyId order.
        writeLeaders(leaders, List.of(policies.get(1), policies.get(0)));
        // Phase 1 orders incomplete policies by valid and observed coverage, descending.
        writeIncomplete(incomplete, List.of(
                new IncompleteVector(policies.get(2), 2, 3),
                new IncompleteVector(policies.get(3), 1, 2)));
        return new Fixture(new ScenarioInputs(scenarioFile, leaders, incomplete),
                required, policies, results);
    }

    private record Fixture(ScenarioInputs inputs, TreeSet<SourceScenario> required,
                           List<PolicyVector> policies, List<ScenarioResult> results) {

    }

    private record IncompleteVector(PolicyVector policy, int valid, int observed) {

    }
}
