package io.euhedral_execution.training.learning;

import io.euhedral_execution.training.data.*;
import io.euhedral_execution.training.merge.MergeRecords.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class Phase1ScenarioLearningReader {
    private static final String SCENARIO_HEADER = "schema_version,scenario_id,environment_id,source_count,available_physical_core_count,source_ratio_numerator,source_ratio_denominator,policy_id,status,total_run_count,accepted_run_count,weak_run_count,uncalibrated_run_count,successful_repetition_count,planned_repetition_count,throughput_p25,throughput_median,throughput_p75,throughput_iqr,median_within_run_relative_iqr,mean_timeout_rate,mean_failure_rate,mean_non_success_rate,bootstrap_median_ci_low,bootstrap_median_ci_high,quality";
    private Phase1ScenarioLearningReader() {}

    public static ScenarioLearningTable read(Phase1ScenarioInputs inputs,
            SortedSet<SourceScenario> required, boolean includeWeak) throws IOException {
        Objects.requireNonNull(inputs);
        required = immutableRequired(required);
        inputs.requireFiles();
        TreeMap<PolicyId, PolicyVector> policies = new TreeMap<>();
        TreeMap<PolicyId, DeclaredCoverage> coverage = new TreeMap<>();
        readLeaderVectors(inputs.robustLeaderVectors(), required.size(), policies, coverage);
        readIncompleteVectors(inputs.incompletePolicyVectors(), required.size(), policies,
                coverage);
        List<String[]> csvRows = Phase1LearningCsv.read(
                inputs.scenarioResults(), SCENARIO_HEADER, 26);
        ArrayList<ScenarioResult> results = new ArrayList<>();
        HashSet<PolicyScenarioKey> identities = new HashSet<>();
        HashSet<PolicyId> seenPolicies = new HashSet<>();
        for (int rowIndex = 0; rowIndex < csvRows.size(); rowIndex++) {
            String[] f = csvRows.get(rowIndex);
            if (!f[0].equals("1")) {
                throw new IOException("Unknown scenario schema at row " + (rowIndex + 2));
            }
            try {
                SourceScenario scenario = SourceScenario.of(f[2], Integer.parseInt(f[3]), Integer.parseInt(f[4]));
                if (!scenario.canonical().equals(f[1])
                        || scenario.ratio().numerator() != Integer.parseInt(f[5])
                        || scenario.ratio().denominator() != Integer.parseInt(f[6]))
                    throw new IllegalArgumentException("Scenario identity mismatch");
                PolicyId id = PolicyId.parse(f[7]); PolicyVector policy = policies.get(id);
                if (policy == null) throw new IllegalArgumentException("Missing policy vector " + id);
                if (!identities.add(new PolicyScenarioKey(id, scenario)))
                    throw new IllegalArgumentException("Duplicate scenario row");
                seenPolicies.add(id);
                results.add(new ScenarioResult(scenario, policy, ScenarioResultStatus.valueOf(f[8]),
                        integer(f[9]), integer(f[10]), integer(f[11]), integer(f[12]), integer(f[13]),
                        integer(f[14]), optional(f[15]), optional(f[16]), optional(f[17]), optional(f[18]),
                        optional(f[19]), optional(f[20]), optional(f[21]), optional(f[22]), optional(f[23]),
                        optional(f[24]), optional(f[25])));
            } catch (RuntimeException error) {
                throw new IOException("Invalid scenario row " + (rowIndex + 2), error);
            }
        }
        if (!seenPolicies.equals(policies.keySet())) throw new IOException("Vector without scenario row");
        for (PolicyId id : policies.keySet()) for (SourceScenario scenario : required)
            if (!identities.contains(new PolicyScenarioKey(id, scenario)))
                throw new IOException("Incomplete policy/scenario Cartesian grid");
        validateCoverage(results, required, coverage);
        return build(results, policies, required, includeWeak);
    }

    public static ScenarioLearningTable fromScenarioResults(Collection<ScenarioResult> results,
            SortedSet<SourceScenario> required, boolean includeWeak) {
        Objects.requireNonNull(results);
        required = immutableRequired(required);
        TreeMap<PolicyId, PolicyVector> policies = new TreeMap<>();
        for (var result : results) {
            PolicyVector previous = policies.putIfAbsent(result.policy().id(), result.policy());
            if (previous != null && !previous.bitwiseEquals(result.policy()))
                throw new IllegalArgumentException("Policy collision");
        }
        HashSet<PolicyScenarioKey> identities = new HashSet<>();
        for (ScenarioResult result : results) {
            if (!identities.add(new PolicyScenarioKey(result.policy().id(), result.scenario()))) {
                throw new IllegalArgumentException("Duplicate scenario row");
            }
        }
        for (PolicyId id : policies.keySet()) {
            for (SourceScenario scenario : required) {
                if (!identities.contains(new PolicyScenarioKey(id, scenario))) {
                    throw new IllegalArgumentException(
                            "Incomplete policy/scenario Cartesian grid");
                }
            }
        }
        return build(results, policies, required, includeWeak);
    }
    private static ScenarioLearningTable build(Collection<ScenarioResult> source,
            SortedMap<PolicyId, PolicyVector> policies, SortedSet<SourceScenario> required,
            boolean includeWeak) {
        ArrayList<ScenarioLearningRow> rows = new ArrayList<>(); int strong=0, weak=0, excluded=0,
                missing=0, noValid=0, noCalibration=0, nonRequired=0;
        HashSet<PolicyScenarioKey> identities = new HashSet<>();
        for (ScenarioResult r : source) {
            if (!identities.add(new PolicyScenarioKey(r.policy().id(), r.scenario())))
                throw new IllegalArgumentException("Duplicate row");
            if (!required.contains(r.scenario())) { nonRequired++; continue; }
            switch (r.status()) {
                case VALID_STRONG -> { rows.add(row(r)); strong++; }
                case VALID_WEAK_OVERRIDE -> { if (includeWeak) { rows.add(row(r)); weak++; } else excluded++; }
                case MISSING -> missing++;
                case NO_VALID_RUN -> noValid++;
                case NO_ACCEPTED_CALIBRATION -> noCalibration++;
            }
        }
        rows.sort(null);
        for (SourceScenario scenario : required)
            if (rows.stream().noneMatch(r -> r.scenario().equals(scenario)))
                throw new InsufficientScenarioLearningDataException("No included row for " + scenario);
        ScenarioDatasetAudit audit = new ScenarioDatasetAudit(policies.size(), required.size(),
                source.size(), strong, weak, excluded, missing, noValid, noCalibration, nonRequired);
        return new ScenarioLearningTable(rows, policies, required, audit, fingerprint(rows, policies, required));
    }
    private static ScenarioLearningRow row(ScenarioResult r) {
        return new ScenarioLearningRow(r.policy(), r.scenario(), r.status(), r.quality().orElseThrow(),
                r.throughputMedian().orElseThrow(), r.bootstrapMedianCiLow().orElseThrow(),
                r.bootstrapMedianCiHigh().orElseThrow(), r.acceptedRunCount(),
                r.medianWithinRunRelativeIqr().orElseThrow(), r.meanNonSuccessRate().orElseThrow());
    }
    private static void readLeaderVectors(java.nio.file.Path path, int requiredScenarioCount,
            TreeMap<PolicyId, PolicyVector> policies,
            TreeMap<PolicyId, DeclaredCoverage> coverage) throws IOException {
        String header = vectorHeader("schema_version,robust_rank,policy_id");
        List<String[]> rows = Phase1LearningCsv.read(path, header, 31);
        int expectedRank = 1;
        for (int row = 0; row < rows.size(); row++) {
            String[] fields = rows.get(row);
            try {
                if (!fields[0].equals("1") || Integer.parseInt(fields[1]) != expectedRank++) {
                    throw new IllegalArgumentException("Leader ranks must be contiguous");
                }
                PolicyId id = addVector(fields, 2, 3, policies);
                coverage.put(id, new DeclaredCoverage(requiredScenarioCount,
                        requiredScenarioCount));
            } catch (RuntimeException error) {
                throw new IOException("Invalid leader vector row " + (row + 2), error);
            }
        }
    }

    private static void readIncompleteVectors(java.nio.file.Path path, int requiredScenarioCount,
            TreeMap<PolicyId, PolicyVector> policies,
            TreeMap<PolicyId, DeclaredCoverage> coverage) throws IOException {
        String header = vectorHeader("schema_version,valid_required_scenario_count,"
                + "observed_required_scenario_count,policy_id");
        List<String[]> rows = Phase1LearningCsv.read(path, header, 32);
        IncompleteOrder previous = null;
        for (int row = 0; row < rows.size(); row++) {
            String[] fields = rows.get(row);
            try {
                if (!fields[0].equals("1")) {
                    throw new IllegalArgumentException("Unknown vector schema");
                }
                int valid = Integer.parseInt(fields[1]);
                int observed = Integer.parseInt(fields[2]);
                PolicyId id = PolicyId.parse(fields[3]);
                if (valid < 0 || valid >= requiredScenarioCount || observed < valid
                        || observed > requiredScenarioCount) {
                    throw new IllegalArgumentException("Invalid incomplete coverage counts");
                }
                IncompleteOrder current = new IncompleteOrder(valid, observed, id);
                if (previous != null && previous.compareTo(current) >= 0) {
                    throw new IllegalArgumentException("Invalid incomplete-policy ordering");
                }
                previous = current;
                PolicyId added = addVector(fields, 3, 4, policies);
                coverage.put(added, new DeclaredCoverage(valid, observed));
            } catch (RuntimeException error) {
                throw new IOException("Invalid incomplete vector row " + (row + 2), error);
            }
        }
    }

    private static PolicyId addVector(String[] fields, int idIndex, int weightIndex,
            TreeMap<PolicyId, PolicyVector> policies) {
        PolicyId id = PolicyId.parse(fields[idIndex]);
        double[] weights = new double[PolicyVector.WIDTH];
        for (int i = 0; i < weights.length; i++) {
            String raw = fields[weightIndex + i];
            if (!raw.matches("[0-9a-f]{16}")) {
                throw new IllegalArgumentException("Malformed raw weight bits");
            }
            weights[i] = Double.longBitsToDouble(Long.parseUnsignedLong(raw, 16));
        }
        PolicyVector vector = PolicyVector.of(weights);
        if (!vector.id().equals(id) || policies.putIfAbsent(id, vector) != null) {
            throw new IllegalArgumentException("Invalid or duplicate policy identity");
        }
        return id;
    }

    private static String vectorHeader(String prefix) {
        StringBuilder header = new StringBuilder(prefix);
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            header.append(",weight_%02d_bits".formatted(i));
        }
        return header.toString();
    }

    private static void validateCoverage(List<ScenarioResult> results,
            SortedSet<SourceScenario> required,
            SortedMap<PolicyId, DeclaredCoverage> declared) throws IOException {
        TreeMap<PolicyId, int[]> actual = new TreeMap<>();
        for (PolicyId policy : declared.keySet()) actual.put(policy, new int[2]);
        for (ScenarioResult result : results) {
            if (!required.contains(result.scenario())) continue;
            int[] counts = actual.get(result.policy().id());
            if (result.totalRunCount() > 0) counts[1]++;
            if (result.quality().isPresent()) counts[0]++;
        }
        for (Map.Entry<PolicyId, DeclaredCoverage> entry : declared.entrySet()) {
            int[] counts = actual.get(entry.getKey());
            if (counts[0] != entry.getValue().valid()
                    || counts[1] != entry.getValue().observed()) {
                throw new IOException("Vector coverage counts disagree for " + entry.getKey());
            }
        }
    }
    private static String fingerprint(List<ScenarioLearningRow> rows,
            SortedMap<PolicyId, PolicyVector> policies, SortedSet<SourceScenario> required) {
        StringBuilder text=new StringBuilder("scenario-learning-table-v1\n");
        required.forEach(s->text.append("required:").append(s.canonical()).append('\n'));
        policies.forEach((id,p)->{ text.append("policy:").append(id.canonical());
            for(double w:p.copyWeights()) text.append('|').append("%016x".formatted(Double.doubleToRawLongBits(w)));
            text.append('\n'); });
        for(var r:rows) text.append("row:").append(r.policy().id()).append('|').append(r.scenario())
                .append('|').append(r.sourceStatus()).append('|').append(bits(r.quality())).append('|')
                .append(bits(r.throughputMedian())).append('|').append(bits(r.bootstrapMedianCiLow()))
                .append('|').append(bits(r.bootstrapMedianCiHigh())).append('|').append(r.acceptedRunCount())
                .append('|').append(bits(r.medianWithinRunRelativeIqr())).append('|')
                .append(bits(r.meanNonSuccessRate())).append('\n');
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String bits(double x){return "%016x".formatted(Double.doubleToRawLongBits(x));}
    private static int integer(String x){return Integer.parseInt(x);}
    private static OptionalDouble optional(String x){return x.isEmpty()?OptionalDouble.empty():OptionalDouble.of(Double.parseDouble(x));}

    private static SortedSet<SourceScenario> immutableRequired(
            SortedSet<SourceScenario> required) {
        Objects.requireNonNull(required);
        TreeSet<SourceScenario> copy = new TreeSet<>(required);
        if (copy.isEmpty()) throw new IllegalArgumentException("Required scenarios are empty");
        return Collections.unmodifiableSortedSet(copy);
    }

    private record PolicyScenarioKey(PolicyId policy, SourceScenario scenario) {
    }

    private record IncompleteOrder(int valid, int observed, PolicyId policy)
            implements Comparable<IncompleteOrder> {
        @Override
        public int compareTo(IncompleteOrder other) {
            int result = Integer.compare(other.valid, valid);
            if (result == 0) result = Integer.compare(other.observed, observed);
            return result != 0 ? result : policy.compareTo(other.policy);
        }
    }

    private record DeclaredCoverage(int valid, int observed) {
    }
}
