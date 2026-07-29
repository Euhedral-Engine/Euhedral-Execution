package io.euhedral_execution.training.packaging;

import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.scheduling.data.ScheduledRun;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PackageDatasetWriter {
    private static final List<String> SCENARIO_HEADER = List.of("schema_version",
            "scenario_id", "environment_id", "source_count",
            "available_physical_core_count", "source_ratio_numerator",
            "source_ratio_denominator", "policy_id", "status", "total_run_count",
            "accepted_run_count", "weak_run_count", "uncalibrated_run_count",
            "successful_repetition_count", "planned_repetition_count", "throughput_p25",
            "throughput_median", "throughput_p75", "throughput_iqr",
            "median_within_run_relative_iqr", "mean_timeout_rate", "mean_failure_rate",
            "mean_non_success_rate", "bootstrap_median_ci_low",
            "bootstrap_median_ci_high", "quality");

    static void writeMeasurements(Path merge, Path target) throws IOException {
        Map<String, List<String>> vectors = vectors(merge);
        Set<String> rankingPolicies = rankingPolicies(merge);
        if (!vectors.keySet().equals(rankingPolicies)) {
            throw new IllegalArgumentException("Vector/ranking policy set mismatch");
        }
        List<List<String>> rows = CanonicalCsv.read(
                merge.resolve("scenario-results.csv"));
        if (rows.isEmpty() || !rows.getFirst().equals(SCENARIO_HEADER)) {
            throw new IllegalArgumentException("Unexpected scenario result schema");
        }
        ArrayList<String> header = new ArrayList<>(SCENARIO_HEADER.subList(0, 8));
        for (int index = 0; index < PolicyVector.WIDTH; index++) {
            header.add("weight_%02d_bits".formatted(index));
        }
        header.addAll(SCENARIO_HEADER.subList(8, SCENARIO_HEADER.size()));
        StringBuilder out = new StringBuilder(CanonicalCsv.row(header));
        Set<String> seenScenarioPolicies = new HashSet<>();
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.size() != SCENARIO_HEADER.size()) {
                throw new IllegalArgumentException("Unexpected scenario result row width");
            }
            List<String> weights = vectors.get(row.get(7));
            if (weights == null) throw new IllegalArgumentException("Scenario policy lacks vector");
            String identity = row.get(1) + "\0" + row.get(7);
            if (!seenScenarioPolicies.add(identity)) {
                throw new IllegalArgumentException("Duplicate scenario policy result");
            }
            ArrayList<String> joined = new ArrayList<>(row.subList(0, 8));
            joined.addAll(weights);
            joined.addAll(row.subList(8, row.size()));
            out.append(CanonicalCsv.row(joined));
        }
        CanonicalFileSupport.write(target, out.toString());
    }

    static void writeBenchmarkReady(PackageSourceSet source, Path target)
            throws IOException {
        ArrayList<String> header = new ArrayList<>(List.of("schema_version", "scenario_id",
                "benchmark_run_id", "schedule_position", "policy_id", "roles"));
        for (int index = 0; index < PolicyVector.WIDTH; index++) {
            header.add("weight_%02d_bits".formatted(index));
        }
        StringBuilder out = new StringBuilder(CanonicalCsv.row(header));
        for (ScheduledRun run : source.scheduleData().runs()) {
            run.policies().forEach(policy -> {
                ArrayList<String> row = new ArrayList<>(List.of("1",
                        run.scenario().canonical(), run.benchmarkRunId(),
                        Integer.toString(policy.schedulePosition()),
                        policy.policy().id().canonical(), policy.roles().stream()
                        .map(Enum::name).sorted().reduce((left, right) ->
                                left + ";" + right).orElseThrow()));
                for (double weight : policy.policy().copyWeights()) {
                    row.add("%016x".formatted(Double.doubleToRawLongBits(weight)));
                }
                out.append(CanonicalCsv.row(row));
            });
        }
        CanonicalFileSupport.write(target, out.toString());
    }

    static void writeRawIndex(PackageSourceSet source, Path target) throws IOException {
        StringBuilder out = new StringBuilder(CanonicalCsv.row(List.of("schema_version",
                "benchmark_run_id", "closed_loop_iteration", "scenario_id",
                "evidence_source", "evidence_origin", "package_relative_path",
                "artifact_sha256", "started_at", "completed_at", "policy_count",
                "observation_count", "complete")));
        for (PackageSourceSet.EvidenceInfo evidence : source.evidence()) {
            var descriptor = evidence.context().descriptor();
            out.append(CanonicalCsv.row(List.of("1", evidence.index().benchmarkRunId(),
                    Integer.toString(descriptor.closedLoopIteration()),
                    descriptor.scenario().canonical(), evidence.index().source().name(),
                    descriptor.evidenceOrigin().name(),
                    "raw-data/bundles/" + descriptor.benchmarkRunId(),
                    evidence.index().bundle().sha256(), descriptor.startedAt().toString(),
                    evidence.context().completedAt().toString(),
                    Integer.toString(evidence.policyCount()),
                    Long.toString(evidence.observationCount()), "true")));
        }
        CanonicalFileSupport.write(target, out.toString());
    }

    private static Map<String, List<String>> vectors(Path merge) throws IOException {
        HashMap<String, List<String>> result = new HashMap<>();
        readVectors(merge.resolve("robust-leaders.vectors.csv"), result);
        readVectors(merge.resolve("incomplete-policies.vectors.csv"), result);
        return Map.copyOf(result);
    }

    private static void readVectors(Path path, Map<String, List<String>> result)
            throws IOException {
        List<List<String>> rows = CanonicalCsv.read(path);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Invalid vector file");
        }
        int policyColumn = rows.getFirst().indexOf("policy_id");
        int weightColumn = rows.getFirst().indexOf("weight_00_bits");
        if (policyColumn < 0 || weightColumn != policyColumn + 1
                || rows.getFirst().size() != weightColumn + PolicyVector.WIDTH) {
            throw new IllegalArgumentException("Invalid vector file");
        }
        for (int index = 1; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            if (row.size() != rows.getFirst().size() || !row.get(0).equals("1")) {
                throw new IllegalArgumentException("Invalid vector row");
            }
            List<String> weights = List.copyOf(row.subList(weightColumn, row.size()));
            if (weights.stream().anyMatch(value -> !value.matches("[0-9a-f]{16}"))
                    || result.put(row.get(policyColumn), weights) != null) {
                throw new IllegalArgumentException("Duplicate or malformed vector");
            }
        }
    }

    private static Set<String> rankingPolicies(Path merge) throws IOException {
        List<List<String>> rows = CanonicalCsv.read(merge.resolve("robust-ranking.csv"));
        HashSet<String> result = new HashSet<>();
        for (int index = 1; index < rows.size(); index++) {
            if (!result.add(rows.get(index).get(2))) {
                throw new IllegalArgumentException("Duplicate ranked policy");
            }
        }
        return Set.copyOf(result);
    }

    private PackageDatasetWriter() {
    }
}
