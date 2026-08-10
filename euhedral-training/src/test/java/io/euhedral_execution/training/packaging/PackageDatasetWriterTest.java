package io.euhedral_execution.training.packaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.scheduling.fixtures.SchedulingFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageDatasetWriterTest {
    @TempDir
    Path temp;

    private static void writeValidView(Path root) throws Exception {
        Files.createDirectories(root);
        var policy = SchedulingFixtures.policy(7);
        List<String> vectorHeader = new ArrayList<>(List.of("schema_version", "robust_rank", "policy_id"));
        for (int index = 0; index < 28; index++) {
            vectorHeader.add("weight_%02d_bits".formatted(index));
        }
        List<String> vector = new ArrayList<>(List.of("1", "1", policy.id().canonical()));
        for (double weight : policy.copyWeights()) {
            vector.add("%016x".formatted(Double.doubleToRawLongBits(weight)));
        }
        Files.writeString(
                root.resolve("robust-leaders.vectors.csv"), CanonicalCsv.row(vectorHeader) + CanonicalCsv.row(vector));
        Files.writeString(root.resolve("incomplete-policies.vectors.csv"), CanonicalCsv.row(vectorHeader));
        Files.writeString(
                root.resolve("robust-ranking.csv"),
                "schema_version,published_rank,policy_id,eligible,required_scenario_count,"
                        + "observed_required_scenario_count,valid_required_scenario_count,"
                        + "coverage_fraction,worst_quality,quality_p25,geometric_mean_quality,"
                        + "cross_scenario_quality_mad,median_relative_iqr,"
                        + "mean_non_success_rate,mean_timeout_rate,missing_scenarios\n"
                        + "1,1," + policy.id().canonical()
                        + ",true,1,1,1,1.0,.5,.5,.5,0,.1,0,0,\n");
        var scenario = SchedulingFixtures.S1;
        Files.writeString(
                root.resolve("scenario-results.csv"),
                "schema_version,scenario_id,environment_id,source_count,"
                        + "available_physical_core_count,source_ratio_numerator,"
                        + "source_ratio_denominator,policy_id,status,total_run_count,"
                        + "accepted_run_count,weak_run_count,uncalibrated_run_count,"
                        + "successful_repetition_count,planned_repetition_count,"
                        + "throughput_p25,throughput_median,throughput_p75,throughput_iqr,"
                        + "median_within_run_relative_iqr,mean_timeout_rate,"
                        + "mean_failure_rate,mean_non_success_rate,bootstrap_median_ci_low,"
                        + "bootstrap_median_ci_high,quality\n"
                        + "1," + scenario.canonical() + "," + scenario.environmentId()
                        + "," + scenario.sourceCount() + ","
                        + scenario.availablePhysicalCoreCount() + ","
                        + scenario.ratio().numerator() + ","
                        + scenario.ratio().denominator() + ","
                        + policy.id().canonical()
                        + ",VALID_STRONG,1,1,0,0,3,3,10.00,11.00,12.00,2.00,"
                        + ".1,0,0,0,9,13,.5\n");
        PackageDatasetWriter.writeMeasurements(root, root.resolve("policy-scenario-measurements.csv"));
        Path vectors = root.resolve("vectors");
        Files.createDirectory(vectors);
        Files.move(root.resolve("robust-leaders.vectors.csv"), vectors.resolve("robust-leaders.vectors.csv"));
        Files.move(
                root.resolve("incomplete-policies.vectors.csv"), vectors.resolve("incomplete-promising.vectors.csv"));
        PackageDatasetWriter.validateMeasurements(root, Set.of(SchedulingFixtures.S1));
    }

    private static void assertInvalidView(Path root) {
        assertThatThrownBy(() -> PackageDatasetWriter.validateMeasurements(root, Set.of(SchedulingFixtures.S1)))
                .isInstanceOfAny(java.io.IOException.class, IllegalArgumentException.class);
    }

    private static void writeRows(Path file, List<List<String>> rows) throws Exception {
        StringBuilder output = new StringBuilder();
        rows.forEach(row -> output.append(CanonicalCsv.row(row)));
        Files.writeString(file, output);
    }

    @Test
    void insertsRawVectorBitsWithoutReformattingMeasurements() throws Exception {
        var policy = SchedulingFixtures.policy(7);
        List<String> vectorHeader = new ArrayList<>(List.of("schema_version", "robust_rank", "policy_id"));
        for (int index = 0; index < 28; index++) vectorHeader.add("weight_%02d_bits".formatted(index));
        List<String> vector = new ArrayList<>(List.of("1", "1", policy.id().canonical()));
        for (double weight : policy.copyWeights()) vector.add("%016x".formatted(Double.doubleToRawLongBits(weight)));
        Files.writeString(
                temp.resolve("robust-leaders.vectors.csv"), CanonicalCsv.row(vectorHeader) + CanonicalCsv.row(vector));
        Files.writeString(temp.resolve("incomplete-policies.vectors.csv"), CanonicalCsv.row(vectorHeader));
        Files.writeString(
                temp.resolve("robust-ranking.csv"),
                "schema_version,published_rank,policy_id,eligible,required_scenario_count,"
                        + "observed_required_scenario_count,valid_required_scenario_count,"
                        + "coverage_fraction,worst_quality,quality_p25,geometric_mean_quality,"
                        + "cross_scenario_quality_mad,median_relative_iqr,mean_non_success_rate,"
                        + "mean_timeout_rate,missing_scenarios\n"
                        + "1,1," + policy.id().canonical() + ",true,1,1,1,1.0,.5,.5,.5,0,.1,0,0,\n");
        String scenario = SchedulingFixtures.S1.canonical();
        Files.writeString(
                temp.resolve("scenario-results.csv"),
                "schema_version,scenario_id,environment_id,source_count,"
                        + "available_physical_core_count,source_ratio_numerator,"
                        + "source_ratio_denominator,policy_id,status,total_run_count,"
                        + "accepted_run_count,weak_run_count,uncalibrated_run_count,"
                        + "successful_repetition_count,planned_repetition_count,throughput_p25,"
                        + "throughput_median,throughput_p75,throughput_iqr,"
                        + "median_within_run_relative_iqr,mean_timeout_rate,mean_failure_rate,"
                        + "mean_non_success_rate,bootstrap_median_ci_low,"
                        + "bootstrap_median_ci_high,quality\n"
                        + "1," + scenario + ",env-a,1,4,1,4," + policy.id().canonical()
                        + ",VALID_STRONG,1,1,0,0,3,3,10.00,11.00,12.00,2.00,.1,0,0,0,9,13,.5\n");
        Path output = temp.resolve("joined.csv");
        PackageDatasetWriter.writeMeasurements(temp, output);
        List<List<String>> rows = CanonicalCsv.read(output);
        assertThat(rows.get(1).subList(8, 36)).containsExactlyElementsOf(vector.subList(3, 31));
        assertThat(rows.get(1).get(43)).isEqualTo("10.00");

        Path vectors = temp.resolve("vectors");
        Files.createDirectory(vectors);
        Files.move(temp.resolve("robust-leaders.vectors.csv"), vectors.resolve("robust-leaders.vectors.csv"));
        Files.move(
                temp.resolve("incomplete-policies.vectors.csv"), vectors.resolve("incomplete-promising.vectors.csv"));
        Files.move(output, temp.resolve("policy-scenario-measurements.csv"));
        PackageDatasetWriter.validateMeasurements(temp);

        List<String> corrupt = new ArrayList<>(rows.get(1));
        corrupt.set(8, "ffffffffffffffff");
        Files.writeString(
                temp.resolve("policy-scenario-measurements.csv"),
                CanonicalCsv.row(rows.getFirst()) + CanonicalCsv.row(corrupt));
        assertThatThrownBy(() -> PackageDatasetWriter.validateMeasurements(temp))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void rejectsEveryMergeVectorJoinAndRankingConflict() throws Exception {
        Path missing = temp.resolve("missing");
        writeValidView(missing);
        List<List<String>> leaders = CanonicalCsv.read(missing.resolve("vectors/robust-leaders.vectors.csv"));
        writeRows(missing.resolve("vectors/robust-leaders.vectors.csv"), List.of(leaders.getFirst()));
        assertInvalidView(missing);

        Path conflicting = temp.resolve("conflicting");
        writeValidView(conflicting);
        leaders = CanonicalCsv.read(conflicting.resolve("vectors/robust-leaders.vectors.csv"));
        List<List<String>> joined = CanonicalCsv.read(conflicting.resolve("policy-scenario-measurements.csv"));
        ArrayList<String> changedLeader = new ArrayList<>(leaders.get(1));
        changedLeader.set(3, "0000000000000000");
        ArrayList<String> changedJoined = new ArrayList<>(joined.get(1));
        changedJoined.set(8, "0000000000000000");
        writeRows(
                conflicting.resolve("vectors/robust-leaders.vectors.csv"), List.of(leaders.getFirst(), changedLeader));
        writeRows(conflicting.resolve("policy-scenario-measurements.csv"), List.of(joined.getFirst(), changedJoined));
        assertInvalidView(conflicting);

        Path duplicateVector = temp.resolve("duplicate-vector");
        writeValidView(duplicateVector);
        leaders = CanonicalCsv.read(duplicateVector.resolve("vectors/robust-leaders.vectors.csv"));
        writeRows(
                duplicateVector.resolve("vectors/robust-leaders.vectors.csv"),
                List.of(leaders.getFirst(), leaders.get(1), leaders.get(1)));
        assertInvalidView(duplicateVector);

        Path unknownScenario = temp.resolve("unknown-scenario");
        writeValidView(unknownScenario);
        List<List<String>> source = CanonicalCsv.read(unknownScenario.resolve("scenario-results.csv"));
        joined = CanonicalCsv.read(unknownScenario.resolve("policy-scenario-measurements.csv"));
        var unknown = SchedulingFixtures.S2;
        ArrayList<String> changedSource = new ArrayList<>(source.get(1));
        ArrayList<String> changedScenarioJoined = new ArrayList<>(joined.get(1));
        List<String> identity = List.of(
                unknown.canonical(),
                unknown.environmentId(),
                Integer.toString(unknown.sourceCount()),
                Integer.toString(unknown.availablePhysicalCoreCount()),
                Integer.toString(unknown.ratio().numerator()),
                Integer.toString(unknown.ratio().denominator()));
        for (int index = 0; index < identity.size(); index++) {
            changedSource.set(index + 1, identity.get(index));
            changedScenarioJoined.set(index + 1, identity.get(index));
        }
        writeRows(unknownScenario.resolve("scenario-results.csv"), List.of(source.getFirst(), changedSource));
        writeRows(
                unknownScenario.resolve("policy-scenario-measurements.csv"),
                List.of(joined.getFirst(), changedScenarioJoined));
        assertThatThrownBy(
                        () -> PackageDatasetWriter.validateMeasurements(unknownScenario, Set.of(SchedulingFixtures.S1)))
                .isInstanceOf(java.io.IOException.class);

        Path duplicateScenario = temp.resolve("duplicate-scenario");
        writeValidView(duplicateScenario);
        source = CanonicalCsv.read(duplicateScenario.resolve("scenario-results.csv"));
        joined = CanonicalCsv.read(duplicateScenario.resolve("policy-scenario-measurements.csv"));
        writeRows(
                duplicateScenario.resolve("scenario-results.csv"),
                List.of(source.getFirst(), source.get(1), source.get(1)));
        writeRows(
                duplicateScenario.resolve("policy-scenario-measurements.csv"),
                List.of(joined.getFirst(), joined.get(1), joined.get(1)));
        assertInvalidView(duplicateScenario);

        Path changedRank = temp.resolve("changed-rank");
        writeValidView(changedRank);
        List<List<String>> ranking = CanonicalCsv.read(changedRank.resolve("robust-ranking.csv"));
        ArrayList<String> rank = new ArrayList<>(ranking.get(1));
        rank.set(1, "2");
        writeRows(changedRank.resolve("robust-ranking.csv"), List.of(ranking.getFirst(), rank));
        assertInvalidView(changedRank);
    }
}
