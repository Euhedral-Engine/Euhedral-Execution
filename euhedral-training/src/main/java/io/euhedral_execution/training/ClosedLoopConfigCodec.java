package io.euhedral_execution.training;

import io.euhedral_execution.training.benchmark.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.EvaluationThresholds;
import io.euhedral_execution.training.learning.FeatureSelectionMode;
import io.euhedral_execution.training.learning.ScenarioTrainingConfig;
import io.euhedral_execution.training.merge.AggregationConfig;
import io.euhedral_execution.training.merge.AnchorSelectionConfig;
import io.euhedral_execution.training.merge.CalibrationAcceptance;
import io.euhedral_execution.training.merge.CalibrationConfig;
import io.euhedral_execution.training.optimization.CandidateGenerationConfig;
import io.euhedral_execution.training.optimization.CmaEsConfig;
import io.euhedral_execution.training.scheduling.CandidateBudgetConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

public final class ClosedLoopConfigCodec {
    private static final java.util.regex.Pattern DECIMAL = java.util.regex.Pattern.compile(
            "[+-]?(?:(?:[0-9]+(?:\\.[0-9]*)?)|(?:\\.[0-9]+))(?:[eE][+-]?[0-9]+)?");
    private static final Set<String> REPEATED = Set.of("scenario.required",
            "run.initial_observation_bundle", "calibration.reference_override");
    private static final Set<String> KNOWN = Set.of(
            "run.workspace", "run.training_run_id", "run.iterations",
            "run.candidate_budget", "run.active_environment_id",
            "run.scenarios_per_iteration", "run.scheduler_seed_hex",
            "run.initial_sobol_cursor", "run.bootstrap_policies",
            "run.initial_calibration_plan", "run.initial_observation_bundle",
            "run.commit_sha", "run.dirty_working_tree", "run.resume", "run.stop_file",
            "scenario.required", "calibration.reference_override",
            "budget.exploration_weight", "budget.carry_forward_weight",
            "budget.leader_revalidation_weight", "budget.disagreement_audit_weight",
            "candidate.screen_rows", "candidate.maximum_prediction_rows",
            "candidate.score_band_weights", "candidate.cma_weight",
            "candidate.score_band_weight", "candidate.direct_sobol_weight",
            "candidate.cma.enabled", "candidate.cma.islands",
            "candidate.cma.generations", "candidate.cma.population_size",
            "candidate.cma.initial_sigma", "candidate.cma.minimum_seed_policies",
            "benchmark.expected_repetitions", "benchmark.sample_duration_nanos",
            "benchmark.liveness_timeout_nanos", "benchmark.frames_per_source",
            "benchmark.reset_timeout_nanos", "benchmark.ordered_frames",
            "anchors.fixed_fraction", "anchors.minimum_fixed_anchors",
            "anchors.maximum_bootstrap_non_success_rate",
            "anchors.maximum_bootstrap_relative_iqr",
            "anchors.allow_imported_bootstrap", "calibration.minimum_strong_anchors",
            "calibration.minimum_weak_anchors", "calibration.maximum_strong_residual",
            "calibration.maximum_weak_residual", "calibration.minimum_log_sigma",
            "calibration.maximum_anchor_weight_share",
            "aggregation.minimum_successful_repetitions",
            "aggregation.minimum_success_fraction", "aggregation.bootstrap_replicates",
            "aggregation.bootstrap_seed_hex", "aggregation.calibration_acceptance",
            "training.split_seed_hex", "training.model_seed_hex", "training.device",
            "training.ensemble_members", "training.loso_evaluation_members",
            "training.ablation_members", "training.max_epochs", "training.patience",
            "training.batch_size", "training.learning_rate", "training.weight_decay",
            "training.label_smoothing", "training.minimum_train_policy_groups",
            "training.minimum_validation_policy_groups",
            "training.minimum_test_policy_groups",
            "training.minimum_train_rows_per_scenario",
            "training.minimum_validation_rows_per_scenario",
            "training.minimum_test_rows_per_scenario",
            "training.include_weak_calibration_rows",
            "training.feature_selection_mode",
            "evaluation.maximum_grouped_macro_mae",
            "evaluation.minimum_grouped_macro_spearman",
            "evaluation.minimum_grouped_macro_precision_at_ten",
            "evaluation.maximum_loso_macro_mae",
            "evaluation.minimum_loso_macro_spearman",
            "evaluation.maximum_loso_worst_scenario_mae",
            "evaluation.minimum_context_mae_improvement",
            "evaluation.minimum_context_spearman_improvement",
            "evaluation.maximum_context_mae_regression",
            "evaluation.maximum_context_spearman_regression",
            "evaluation.minimum_counts_cross_environment_mae_improvement",
            "evaluation.maximum_counts_spearman_regression",
            "evaluation.maximum_counts_worst_environment_mae_regression");

    public static ClosedLoopConfig read(Path path) throws IOException {
        Path file = path.toAbsolutePath().normalize();
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        if (bytes.length == 0 || bytes[bytes.length - 1] != '\n') {
            throw new IllegalArgumentException("Configuration requires a final LF");
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            throw new IllegalArgumentException("Configuration must not contain a BOM");
        }
        for (byte value : bytes) {
            if (value == '\r') {
                throw new IllegalArgumentException("Configuration must use LF line endings");
            }
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Configuration must be valid UTF-8");
        }
        LinkedHashMap<String, List<Value>> values = parse(text);
        return build(file.getParent(), values);
    }

    public static String example() {
        return """
                run.workspace=workspace
                run.training_run_id=example
                run.iterations=3
                run.candidate_budget=1024
                run.active_environment_id=machine-a
                run.bootstrap_policies=bootstrap-policies.vectors.csv
                run.commit_sha=0000000000000000000000000000000000000000
                run.dirty_working_tree=false
                scenario.required=s1-machine-a-src1-core32-r1of32
                scenario.required=s1-machine-a-src32-core32-r1of1
                """;
    }

    private static LinkedHashMap<String, List<Value>> parse(String text) {
        LinkedHashMap<String, List<Value>> result = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length - 1; index++) {
            String raw = lines[index];
            String stripped = raw.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            int equals = raw.indexOf('=');
            if (equals < 1) {
                throw line(index, "Expected key=value");
            }
            String key = raw.substring(0, equals).trim();
            String value = raw.substring(equals + 1).trim();
            if (!KNOWN.contains(key)) {
                throw line(index, "Unknown key " + key);
            }
            if (value.isEmpty()) {
                throw line(index, "Empty value for " + key);
            }
            if (value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0) {
                throw line(index, "Malformed escape or path for " + key);
            }
            List<Value> entries = result.computeIfAbsent(key, ignored -> new ArrayList<>());
            if (!REPEATED.contains(key) && !entries.isEmpty()) {
                throw line(index, "Duplicate key " + key);
            }
            if (REPEATED.contains(key)
                    && entries.stream().anyMatch(entry -> entry.text.equals(value))) {
                throw line(index, "Duplicate list value for " + key);
            }
            entries.add(new Value(value, index + 1));
        }
        return result;
    }

    private static ClosedLoopConfig build(Path base,
            LinkedHashMap<String, List<Value>> values) {
        Parser parser = new Parser(base, values);
        Path workspace = parser.requiredPath("run.workspace");
        String runId = parser.required("run.training_run_id");
        int iterations = parser.requiredInt("run.iterations");
        int candidateBudget = parser.requiredInt("run.candidate_budget");
        String environment = parser.required("run.active_environment_id");
        TreeSet<SourceScenario> scenarios = new TreeSet<>();
        for (Value value : parser.requiredList("scenario.required")) {
            parser.addUnique(scenarios, parser.at(value,
                    () -> SourceScenario.parse(value.text)), "scenario.required", value.line);
        }
        Optional<Path> bootstrap = parser.optionalPath("run.bootstrap_policies");
        Optional<Path> calibrationPlan = parser.optionalPath("run.initial_calibration_plan");
        if (bootstrap.isPresent() == calibrationPlan.isPresent()) {
            throw new IllegalArgumentException("Exactly one bootstrap source is required");
        }
        List<Path> bundles = parser.paths("run.initial_observation_bundle");
        if (!bundles.isEmpty() && calibrationPlan.isEmpty()) {
            throw new IllegalArgumentException(
                    "run.initial_observation_bundle requires run.initial_calibration_plan");
        }
        Map<SourceScenario, String> overrides = parser.referenceOverrides();

        CandidateBudgetConfig budget = new CandidateBudgetConfig(
                parser.integer("budget.exploration_weight", 68),
                parser.integer("budget.carry_forward_weight", 25),
                parser.integer("budget.leader_revalidation_weight", 2),
                parser.integer("budget.disagreement_audit_weight", 5));
        CmaEsConfig cma = new CmaEsConfig(
                parser.bool("candidate.cma.enabled", true),
                parser.integer("candidate.cma.islands", 4),
                parser.integer("candidate.cma.generations", 12),
                parser.integer("candidate.cma.population_size", 96),
                parser.decimal("candidate.cma.initial_sigma", .20),
                parser.integer("candidate.cma.minimum_seed_policies", 10));
        CandidateGenerationConfig generation = new CandidateGenerationConfig(
                parser.integer("candidate.screen_rows", 2_097_152),
                parser.integer("candidate.maximum_prediction_rows", 16_384),
                parser.intList("candidate.score_band_weights",
                        new int[]{1, 1, 1, 1, 2, 2, 3, 5, 8, 16}),
                parser.integer("candidate.cma_weight", 8),
                parser.integer("candidate.score_band_weight", 7),
                parser.integer("candidate.direct_sobol_weight", 1), cma);
        BenchmarkExecutionConfig benchmark = new BenchmarkExecutionConfig(
                parser.integer("benchmark.expected_repetitions", 10),
                parser.longInteger("benchmark.sample_duration_nanos", 200_000_000L),
                parser.longInteger("benchmark.liveness_timeout_nanos", 50_000_000L),
                parser.integer("benchmark.frames_per_source", 100_000),
                parser.longInteger("benchmark.reset_timeout_nanos", 2_000_000_000L),
                parser.bool("benchmark.ordered_frames", false));
        AnchorSelectionConfig anchors = new AnchorSelectionConfig(
                parser.decimal("anchors.fixed_fraction", .02),
                parser.integer("anchors.minimum_fixed_anchors", 5),
                parser.decimal("anchors.maximum_bootstrap_non_success_rate", .10),
                parser.decimal("anchors.maximum_bootstrap_relative_iqr", .25),
                parser.bool("anchors.allow_imported_bootstrap", false));
        CalibrationConfig calibration = new CalibrationConfig(
                parser.integer("calibration.minimum_strong_anchors", 5),
                parser.integer("calibration.minimum_weak_anchors", 3),
                parser.decimal("calibration.maximum_strong_residual", .05),
                parser.decimal("calibration.maximum_weak_residual", .15),
                parser.decimal("calibration.minimum_log_sigma", .01),
                parser.decimal("calibration.maximum_anchor_weight_share", .25));
        AggregationConfig aggregation = new AggregationConfig(
                parser.integer("aggregation.minimum_successful_repetitions", 3),
                parser.decimal("aggregation.minimum_success_fraction", .5),
                parser.integer("aggregation.bootstrap_replicates", 1000),
                parser.seed("aggregation.bootstrap_seed_hex", 0x6a09e667f3bcc909L),
                parser.enumeration("aggregation.calibration_acceptance",
                        CalibrationAcceptance.STRONG_ONLY, CalibrationAcceptance.class));
        EvaluationThresholds thresholds = new EvaluationThresholds(
                parser.decimal("evaluation.maximum_grouped_macro_mae", .20),
                parser.decimal("evaluation.minimum_grouped_macro_spearman", .50),
                parser.decimal("evaluation.minimum_grouped_macro_precision_at_ten", .20),
                parser.decimal("evaluation.maximum_loso_macro_mae", .25),
                parser.decimal("evaluation.minimum_loso_macro_spearman", .35),
                parser.decimal("evaluation.maximum_loso_worst_scenario_mae", .35),
                parser.decimal("evaluation.minimum_context_mae_improvement", .01),
                parser.decimal("evaluation.minimum_context_spearman_improvement", .05),
                parser.decimal("evaluation.maximum_context_mae_regression", .01),
                parser.decimal("evaluation.maximum_context_spearman_regression", .02),
                parser.decimal("evaluation.minimum_counts_cross_environment_mae_improvement",
                        .01),
                parser.decimal("evaluation.maximum_counts_spearman_regression", .02),
                parser.decimal("evaluation.maximum_counts_worst_environment_mae_regression",
                        .02));
        ScenarioTrainingConfig training = new ScenarioTrainingConfig(
                parser.seed("training.split_seed_hex", 0x243f6a8885a308d3L),
                parser.seed("training.model_seed_hex", 0x13198a2e03707344L),
                parser.string("training.device", "auto"),
                parser.integer("training.ensemble_members", 5),
                parser.integer("training.loso_evaluation_members", 1),
                parser.integer("training.ablation_members", 3),
                parser.integer("training.max_epochs", 250),
                parser.integer("training.patience", 20),
                parser.integer("training.batch_size", 0),
                parser.floating("training.learning_rate", .001f),
                parser.floating("training.weight_decay", .0001f),
                parser.floating("training.label_smoothing", .02f),
                parser.integer("training.minimum_train_policy_groups", 40),
                parser.integer("training.minimum_validation_policy_groups", 10),
                parser.integer("training.minimum_test_policy_groups", 10),
                parser.integer("training.minimum_train_rows_per_scenario", 30),
                parser.integer("training.minimum_validation_rows_per_scenario", 5),
                parser.integer("training.minimum_test_rows_per_scenario", 5),
                parser.bool("training.include_weak_calibration_rows", false),
                parser.enumeration("training.feature_selection_mode",
                        FeatureSelectionMode.RATIO_ONLY, FeatureSelectionMode.class), thresholds);
        try {
            return new ClosedLoopConfig(workspace, runId, iterations, candidateBudget, scenarios,
                    environment, parser.integer("run.scenarios_per_iteration", 2),
                    parser.seed("run.scheduler_seed_hex", 0x6a09e667f3bcc909L),
                    parser.longInteger("run.initial_sobol_cursor", 131_072L),
                    bootstrap, calibrationPlan, bundles, overrides,
                    parser.required("run.commit_sha"),
                    parser.requiredBoolean("run.dirty_working_tree"), budget, generation,
                    benchmark, anchors, calibration, aggregation, training,
                    parser.bool("run.resume", true),
                    parser.path("run.stop_file", workspace.resolve("STOP")));
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IllegalArgumentException("Invalid closed-loop configuration: "
                    + error.getMessage(), error);
        }
    }

    private static IllegalArgumentException line(int zeroBasedLine, String message) {
        return new IllegalArgumentException("Line " + (zeroBasedLine + 1) + ": " + message);
    }

    private record Value(String text, int line) {
    }

    private static final class Parser {
        private final Path base;
        private final Map<String, List<Value>> values;

        private Parser(Path base, Map<String, List<Value>> values) {
            this.base = base;
            this.values = values;
        }

        private String required(String key) {
            Value value = one(key);
            if (value == null) {
                throw new IllegalArgumentException("Missing required key " + key);
            }
            return value.text;
        }

        private boolean requiredBoolean(String key) {
            required(key);
            return bool(key, false);
        }

        private int requiredInt(String key) {
            required(key);
            return integer(key, 0);
        }

        private Path requiredPath(String key) {
            required(key);
            return path(key, base);
        }

        private String string(String key, String defaultValue) {
            Value value = one(key);
            return value == null ? defaultValue : value.text;
        }

        private int integer(String key, int defaultValue) {
            Value value = one(key);
            return value == null ? defaultValue : at(value, () -> {
                requireDecimal(value.text);
                return Integer.parseInt(value.text);
            });
        }

        private long longInteger(String key, long defaultValue) {
            Value value = one(key);
            return value == null ? defaultValue : at(value, () -> {
                requireDecimal(value.text);
                return Long.parseLong(value.text);
            });
        }

        private double decimal(String key, double defaultValue) {
            Value value = one(key);
            return value == null ? defaultValue : at(value, () -> {
                requireFloatingDecimal(value.text);
                double result = Double.parseDouble(value.text);
                if (!Double.isFinite(result)) {
                    throw new IllegalArgumentException("Non-finite decimal");
                }
                return result;
            });
        }

        private float floating(String key, float defaultValue) {
            Value value = one(key);
            return value == null ? defaultValue : at(value, () -> {
                requireFloatingDecimal(value.text);
                float result = Float.parseFloat(value.text);
                if (!Float.isFinite(result)) {
                    throw new IllegalArgumentException("Non-finite float");
                }
                return result;
            });
        }

        private boolean bool(String key, boolean defaultValue) {
            Value value = one(key);
            if (value == null) {
                return defaultValue;
            }
            if (!value.text.equals("true") && !value.text.equals("false")) {
                throw at(value, () -> new IllegalArgumentException(
                        "Expected true or false for " + key));
            }
            return Boolean.parseBoolean(value.text);
        }

        private long seed(String key, long defaultValue) {
            Value value = one(key);
            return value == null ? defaultValue : at(value, () -> {
                if (!value.text.matches("[0-9a-f]{16}")) {
                    throw new IllegalArgumentException("Expected 16 lower-case hex digits");
                }
                return Long.parseUnsignedLong(value.text, 16);
            });
        }

        private int[] intList(String key, int[] defaults) {
            Value value = one(key);
            if (value == null) {
                return defaults.clone();
            }
            return at(value, () -> {
                String[] fields = value.text.split(",", -1);
                int[] result = new int[fields.length];
                for (int i = 0; i < result.length; i++) {
                    if (fields[i].isEmpty()) {
                        throw new IllegalArgumentException("Empty list element");
                    }
                    requireDecimal(fields[i]);
                    result[i] = Integer.parseInt(fields[i]);
                }
                return result;
            });
        }

        private <E extends Enum<E>> E enumeration(String key, E defaultValue, Class<E> type) {
            Value value = one(key);
            return value == null ? defaultValue : at(value,
                    () -> Enum.valueOf(type, value.text));
        }

        private Path path(String key, Path defaultValue) {
            Value value = one(key);
            if (value == null) {
                return defaultValue.toAbsolutePath().normalize();
            }
            return at(value, () -> resolve(value.text));
        }

        private Optional<Path> optionalPath(String key) {
            Value value = one(key);
            return value == null ? Optional.empty() : Optional.of(resolve(value.text));
        }

        private List<Path> paths(String key) {
            ArrayList<Path> result = new ArrayList<>();
            HashSet<Path> unique = new HashSet<>();
            for (Value value : values.getOrDefault(key, List.of())) {
                Path path = at(value, () -> resolve(value.text));
                if (!unique.add(path)) {
                    throw new IllegalArgumentException("Line " + value.line
                            + ": duplicate path for " + key);
                }
                result.add(path);
            }
            return List.copyOf(result);
        }

        private List<Value> requiredList(String key) {
            List<Value> result = values.getOrDefault(key, List.of());
            if (result.isEmpty()) {
                throw new IllegalArgumentException("Missing required key " + key);
            }
            return result;
        }

        private Map<SourceScenario, String> referenceOverrides() {
            HashMap<SourceScenario, String> result = new HashMap<>();
            for (Value value : values.getOrDefault(
                    "calibration.reference_override", List.of())) {
                at(value, () -> {
                    int separator = value.text.indexOf('|');
                    if (separator <= 0 || separator != value.text.lastIndexOf('|')
                            || separator == value.text.length() - 1) {
                        throw new IllegalArgumentException("Malformed reference override");
                    }
                    SourceScenario scenario = SourceScenario.parse(
                            value.text.substring(0, separator));
                    String run = value.text.substring(separator + 1);
                    if (!run.matches("[a-z0-9][a-z0-9._-]{0,95}")
                            || result.putIfAbsent(scenario, run) != null) {
                        throw new IllegalArgumentException("Duplicate or malformed override");
                    }
                    return null;
                });
            }
            return Map.copyOf(result);
        }

        private Path resolve(String text) {
            Path value = Path.of(text);
            return (value.isAbsolute() ? value : base.resolve(value))
                    .toAbsolutePath().normalize();
        }

        private Value one(String key) {
            List<Value> found = values.get(key);
            return found == null ? null : found.getFirst();
        }

        private <T> T at(Value value, CheckedSupplier<T> supplier) {
            try {
                return supplier.get();
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("Line " + value.line + ": "
                        + error.getMessage(), error);
            }
        }

        private <T> void addUnique(Set<T> target, T value, String key, int line) {
            if (!target.add(value)) {
                throw new IllegalArgumentException("Line " + line
                        + ": duplicate value for " + key);
            }
        }

        private static void requireDecimal(String value) {
            if (!value.matches("-?[0-9]+")) {
                throw new IllegalArgumentException("Expected decimal integer");
            }
        }

        private static void requireFloatingDecimal(String value) {
            if (!DECIMAL.matcher(value).matches()) {
                throw new IllegalArgumentException("Expected finite decimal number");
            }
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get();
    }

    private ClosedLoopConfigCodec() {
    }
}
