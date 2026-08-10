package io.euhedral_execution.training.scheduling.io;

import io.euhedral_execution.training.DataMerger;
import io.euhedral_execution.training.checkpoint.ArtifactFingerprint;
import io.euhedral_execution.training.data.PolicyId;
import io.euhedral_execution.training.data.PolicyRegistry;
import io.euhedral_execution.training.data.PolicyVector;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.data.io.CanonicalCsv;
import io.euhedral_execution.training.merge.PolicyComparator;
import io.euhedral_execution.training.merge.data.MergeRecords.RobustPolicySummary;
import io.euhedral_execution.training.merge.data.MergeRecords.ScenarioResultStatus;
import io.euhedral_execution.training.scheduling.data.OptimizationCorpusView;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OptimizationCorpusReader {
    private static final List<String> RANKING_HEADER = List.of(
            "schema_version",
            "published_rank",
            "policy_id",
            "eligible",
            "required_scenario_count",
            "observed_required_scenario_count",
            "valid_required_scenario_count",
            "coverage_fraction",
            "worst_quality",
            "quality_p25",
            "geometric_mean_quality",
            "cross_scenario_quality_mad",
            "median_relative_iqr",
            "mean_non_success_rate",
            "mean_timeout_rate",
            "missing_scenarios");
    private static final List<String> COVERAGE_HEADER = List.of(
            "schema_version",
            "policy_id",
            "eligible",
            "required_scenario_count",
            "observed_required_scenario_count",
            "valid_required_scenario_count",
            "measured_scenarios",
            "missing_scenarios",
            "rejected_scenarios");

    private static final Logger LOGGER = LoggerFactory.getLogger(OptimizationCorpusReader.class);

    public static OptimizationCorpusView read(
            DataMerger.MergeArtifacts artifacts, SortedSet<SourceScenario> requiredScenarios) throws IOException {
        PolicyRegistry registry = new PolicyRegistry();
        TreeMap<PolicyId, PolicyVector> policies = new TreeMap<>();
        readVectors(artifacts.robustLeaderVectors(), registry, policies, true);
        readVectors(artifacts.incompleteVectors(), registry, policies, false);

        List<List<String>> coverageRows = CanonicalCsv.read(artifacts.coverageReport());
        requireHeader(coverageRows, COVERAGE_HEADER);
        TreeMap<PolicyId, CoverageRow> coverageMetadata = new TreeMap<>();
        for (int i = 1; i < coverageRows.size(); i++) {
            List<String> row = requireWidth(coverageRows.get(i), 9);
            version(row.get(0));
            PolicyId id = PolicyId.parse(row.get(1));
            CoverageRow value = new CoverageRow(
                    bool(row.get(2)),
                    integer(row.get(3)),
                    integer(row.get(4)),
                    integer(row.get(5)),
                    scenarios(row.get(6), requiredScenarios),
                    scenarios(row.get(7), requiredScenarios),
                    scenarios(row.get(8), requiredScenarios));
            if (coverageMetadata.put(id, value) != null) {
                throw new IllegalArgumentException("Duplicate coverage policy " + id);
            }
        }

        TreeMap<PolicyId, SortedMap<SourceScenario, ScenarioResultStatus>> coverage =
                readScenarioStatuses(artifacts.scenarioResults(), requiredScenarios);
        List<List<String>> rankingRows = CanonicalCsv.read(artifacts.robustRanking());
        requireHeader(rankingRows, RANKING_HEADER);
        TreeMap<PolicyId, RobustPolicySummary> summaries = new TreeMap<>();
        ArrayList<RobustPolicySummary> eligible = new ArrayList<>();
        int expectedRank = 1;
        for (int i = 1; i < rankingRows.size(); i++) {
            List<String> row = requireWidth(rankingRows.get(i), 16);
            version(row.get(0));
            PolicyId id = PolicyId.parse(row.get(2));
            PolicyVector policy = policies.get(id);
            CoverageRow c = coverageMetadata.get(id);
            if (policy == null || c == null || !coverage.containsKey(id)) {
                throw new IllegalArgumentException("Incomplete Phase 1 join for " + id);
            }
            validateCoverage(c, coverage.get(id), requiredScenarios);
            boolean isEligible = bool(row.get(3));
            if (isEligible != c.eligible()
                    || integer(row.get(4)) != c.required()
                    || integer(row.get(5)) != c.observed()
                    || integer(row.get(6)) != c.valid()) {
                throw new IllegalArgumentException("Ranking/coverage disagreement for " + id);
            }
            String publishedRank = row.get(1);
            if (isEligible) {
                if (integer(publishedRank) != expectedRank++) {
                    throw new IllegalArgumentException("Published rank gap");
                }
            } else if (!publishedRank.isEmpty()) {
                throw new IllegalArgumentException("Incomplete policy has a published rank");
            }
            if (!scenarios(row.get(15), requiredScenarios).equals(c.missing())) {
                throw new IllegalArgumentException("Missing scenario disagreement");
            }
            RobustPolicySummary summary = new RobustPolicySummary(
                    policy,
                    isEligible,
                    integer(row.get(4)),
                    integer(row.get(5)),
                    integer(row.get(6)),
                    finite(row.get(7)),
                    optional(row.get(8)),
                    optional(row.get(9)),
                    optional(row.get(10)),
                    optional(row.get(11)),
                    optional(row.get(12)),
                    optional(row.get(13)),
                    optional(row.get(14)),
                    c.measured(),
                    c.missing(),
                    c.rejected());
            if (summaries.put(id, summary) != null) {
                throw new IllegalArgumentException("Duplicate ranking policy");
            }
            if (isEligible) {
                eligible.add(summary);
            }
        }
        if (!summaries.keySet().equals(policies.keySet())
                || !summaries.keySet().equals(coverageMetadata.keySet())
                || !summaries.keySet().equals(coverage.keySet())) {
            throw new IllegalArgumentException("Phase 1 datasets do not have the same policies");
        }
        List<RobustPolicySummary> sortedEligible =
                eligible.stream().sorted(PolicyComparator.BEST_FIRST).toList();
        if (!eligible.equals(sortedEligible)) {
            throw new IllegalArgumentException("Robust ranking is not in authoritative order");
        }
        return new OptimizationCorpusView(
                policies,
                sortedEligible,
                summaries,
                coverage,
                ArtifactFingerprint.sha256(artifacts.robustRanking().getParent()));
    }

    private static void readVectors(
            Path file, PolicyRegistry registry, SortedMap<PolicyId, PolicyVector> policies, boolean eligible)
            throws IOException {
        List<List<String>> rows = CanonicalCsv.read(file);
        ArrayList<String> expectedHeader = new ArrayList<>(
                eligible
                        ? List.of("schema_version", "robust_rank", "policy_id")
                        : List.of(
                                "schema_version",
                                "valid_required_scenario_count",
                                "observed_required_scenario_count",
                                "policy_id"));
        for (int i = 0; i < PolicyVector.WIDTH; i++) {
            expectedHeader.add("weight_%02d_bits".formatted(i));
        }
        requireHeader(rows, expectedHeader);
        int policyColumn = eligible ? 2 : 3;
        int weightsColumn = policyColumn + 1;
        IncompleteOrder previous = null;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = requireWidth(rows.get(i), eligible ? 31 : 32);
            version(row.get(0));
            if (eligible && integer(row.get(1)) != i) {
                throw new IllegalArgumentException("Robust vector rank gap");
            }
            double[] weights = new double[PolicyVector.WIDTH];
            for (int weight = 0; weight < weights.length; weight++) {
                weights[weight] = Double.longBitsToDouble(hex(row.get(weightsColumn + weight)));
            }
            PolicyVector policy = registry.register(PolicyVector.of(weights));
            if (!policy.id().equals(PolicyId.parse(row.get(policyColumn)))) {
                throw new IllegalArgumentException("Vector policy ID mismatch");
            }
            if (policies.put(policy.id(), policy) != null) {
                throw new IllegalArgumentException("Duplicate vector policy");
            }
            if (!eligible) {
                IncompleteOrder current = new IncompleteOrder(integer(row.get(1)), integer(row.get(2)), policy.id());
                if (previous != null && previous.compareTo(current) >= 0) {
                    throw new IllegalArgumentException("Incomplete vectors are not deterministic");
                }
                previous = current;
            }
        }
    }

    private static void validateCoverage(
            CoverageRow declared,
            SortedMap<SourceScenario, ScenarioResultStatus> statuses,
            SortedSet<SourceScenario> required) {
        TreeSet<SourceScenario> measured = new TreeSet<>();
        TreeSet<SourceScenario> missing = new TreeSet<>();
        TreeSet<SourceScenario> rejected = new TreeSet<>();
        int valid = 0;
        for (SourceScenario scenario : required) {
            ScenarioResultStatus status = statuses.get(scenario);
            if (status == ScenarioResultStatus.MISSING) {
                missing.add(scenario);
            } else if (status == ScenarioResultStatus.VALID_STRONG
                    || status == ScenarioResultStatus.VALID_WEAK_OVERRIDE) {
                measured.add(scenario);
                valid++;
            } else {
                rejected.add(scenario);
            }
        }
        if (declared.required() != required.size()
                || declared.observed() != measured.size() + rejected.size()
                || declared.valid() != valid
                || !declared.measured().equals(measured)
                || !declared.missing().equals(missing)
                || !declared.rejected().equals(rejected)
                || declared.eligible() != (valid == required.size())) {
            throw new IllegalArgumentException("Coverage report does not recompute");
        }
    }

    private static TreeMap<PolicyId, SortedMap<SourceScenario, ScenarioResultStatus>> readScenarioStatuses(
            Path file, SortedSet<SourceScenario> required) throws IOException {
        List<List<String>> rows = CanonicalCsv.read(file);
        if (rows.isEmpty()
                || rows.getFirst().size() != 26
                || !rows.getFirst().get(0).equals("schema_version")
                || !rows.getFirst().get(1).equals("scenario_id")
                || !rows.getFirst().get(7).equals("policy_id")
                || !rows.getFirst().get(8).equals("status")) {
            throw new IllegalArgumentException("Invalid scenario-results header");
        }
        TreeMap<PolicyId, SortedMap<SourceScenario, ScenarioResultStatus>> result = new TreeMap<>();
        SourceScenario previousScenario = null;
        PolicyId previousPolicy = null;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = requireWidth(rows.get(i), 26);
            version(row.get(0));
            SourceScenario scenario = SourceScenario.parse(row.get(1));
            PolicyId policy = PolicyId.parse(row.get(7));
            if (previousScenario != null
                    && (scenario.compareTo(previousScenario) < 0
                            || scenario.equals(previousScenario) && policy.compareTo(previousPolicy) <= 0)) {
                throw new IllegalArgumentException("Scenario results are not deterministic");
            }
            previousScenario = scenario;
            previousPolicy = policy;
            if (result.computeIfAbsent(policy, ignored -> new TreeMap<>())
                            .put(scenario, ScenarioResultStatus.valueOf(row.get(8)))
                    != null) {
                throw new IllegalArgumentException("Duplicate scenario result");
            }
        }
        for (var entry : result.entrySet()) {
            if (!entry.getValue().keySet().containsAll(required)) {
                LOGGER.error(
                        "Entry: {} ValueKeySet: {} Required: {}, File: {}",
                        entry.getKey(),
                        entry.getValue().keySet(),
                        required,
                        file);
                throw new IllegalArgumentException("Incomplete scenario grid for " + entry.getKey());
            }
        }
        return result;
    }

    private static void requireHeader(List<List<String>> rows, List<String> header) {
        if (rows.isEmpty() || !rows.getFirst().equals(header)) {
            throw new IllegalArgumentException("Unexpected CSV header");
        }
    }

    private static List<String> requireWidth(List<String> row, int width) {
        if (row.size() != width) {
            throw new IllegalArgumentException("Unexpected CSV row width");
        }
        return row;
    }

    private static SortedSet<SourceScenario> scenarios(String value, SortedSet<SourceScenario> required) {
        TreeSet<SourceScenario> result = new TreeSet<>();
        if (!value.isEmpty()) {
            for (String item : value.split(";")) {
                SourceScenario scenario = SourceScenario.parse(item);
                if (!required.contains(scenario) || !result.add(scenario)) {
                    throw new IllegalArgumentException("Invalid scenario set");
                }
            }
        }
        return java.util.Collections.unmodifiableSortedSet(result);
    }

    private static void version(String value) {
        if (!value.equals("1")) {
            throw new IllegalArgumentException("Unsupported schema version");
        }
    }

    private static int integer(String value) {
        return Integer.parseInt(value);
    }

    private static boolean bool(String value) {
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException("Invalid boolean");
        }
        return Boolean.parseBoolean(value);
    }

    private static double finite(String value) {
        double result = Double.parseDouble(value);
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("Non-finite number");
        }
        return result;
    }

    private static OptionalDouble optional(String value) {
        return value.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(finite(value));
    }

    private static long hex(String value) {
        if (!value.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("Invalid raw-bit field");
        }
        return Long.parseUnsignedLong(value, 16);
    }

    private record CoverageRow(
            boolean eligible,
            int required,
            int observed,
            int valid,
            SortedSet<SourceScenario> measured,
            SortedSet<SourceScenario> missing,
            SortedSet<SourceScenario> rejected) {}

    private record IncompleteOrder(int valid, int observed, PolicyId policy) implements Comparable<IncompleteOrder> {
        @Override
        public int compareTo(IncompleteOrder other) {
            int result = Integer.compare(other.valid, valid);
            if (result == 0) {
                result = Integer.compare(other.observed, observed);
            }
            return result != 0 ? result : policy.compareTo(other.policy);
        }
    }

    private OptimizationCorpusReader() {}
}
