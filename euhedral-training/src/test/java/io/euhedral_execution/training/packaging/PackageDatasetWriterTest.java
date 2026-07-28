package io.euhedral_execution.training.packaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.scheduling.fixtures.SchedulingFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageDatasetWriterTest {
    @TempDir
    Path temp;

    @Test
    void insertsRawVectorBitsWithoutReformattingMeasurements() throws Exception {
        var policy = SchedulingFixtures.policy(7);
        List<String> vectorHeader = new ArrayList<>(List.of("schema_version", "robust_rank",
                "policy_id"));
        for (int index = 0; index < 28; index++) vectorHeader.add(
                "weight_%02d_bits".formatted(index));
        List<String> vector = new ArrayList<>(List.of("1", "1", policy.id().canonical()));
        for (double weight : policy.copyWeights()) vector.add(
                "%016x".formatted(Double.doubleToRawLongBits(weight)));
        Files.writeString(temp.resolve("robust-leaders.vectors.csv"),
                CanonicalCsv.row(vectorHeader) + CanonicalCsv.row(vector));
        Files.writeString(temp.resolve("incomplete-policies.vectors.csv"),
                CanonicalCsv.row(vectorHeader));
        Files.writeString(temp.resolve("robust-ranking.csv"),
                "schema_version,published_rank,policy_id,eligible,required_scenario_count,"
                + "observed_required_scenario_count,valid_required_scenario_count,"
                + "coverage_fraction,worst_quality,quality_p25,geometric_mean_quality,"
                + "cross_scenario_quality_mad,median_relative_iqr,mean_non_success_rate,"
                + "mean_timeout_rate,missing_scenarios\n"
                + "1,1," + policy.id().canonical() + ",true,1,1,1,1.0,.5,.5,.5,0,.1,0,0,\n");
        String scenario = SchedulingFixtures.S1.canonical();
        Files.writeString(temp.resolve("scenario-results.csv"),
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
        assertThat(rows.get(1).subList(8, 36)).containsExactlyElementsOf(
                vector.subList(3, 31));
        assertThat(rows.get(1).get(43)).isEqualTo("10.00");

        Path vectors = temp.resolve("vectors");
        Files.createDirectory(vectors);
        Files.move(temp.resolve("robust-leaders.vectors.csv"),
                vectors.resolve("robust-leaders.vectors.csv"));
        Files.move(temp.resolve("incomplete-policies.vectors.csv"),
                vectors.resolve("incomplete-promising.vectors.csv"));
        Files.move(output, temp.resolve("policy-scenario-measurements.csv"));
        PackageDatasetWriter.validateMeasurements(temp);

        List<String> corrupt = new ArrayList<>(rows.get(1));
        corrupt.set(8, "ffffffffffffffff");
        Files.writeString(temp.resolve("policy-scenario-measurements.csv"),
                CanonicalCsv.row(rows.getFirst()) + CanonicalCsv.row(corrupt));
        assertThatThrownBy(() -> PackageDatasetWriter.validateMeasurements(temp))
                .isInstanceOf(java.io.IOException.class);
    }
}
