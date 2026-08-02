package io.euhedral_execution.training.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.checkpoint.ClosedLoopConfigFingerprint;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.learning.enums.FeatureSelectionMode;
import io.euhedral_execution.training.merge.enums.CalibrationAcceptance;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClosedLoopConfigCodecTest {
    @TempDir
    Path temp;

    @Test
    void minimalConfigurationBuildsTypedDefaultsAndResolvesPaths() throws Exception {
        Path configFile = write("config/closed-loop.conf", """
                # typed configuration

                run.workspace=../workspace
                run.training_run_id=test-run
                run.iterations=3
                run.candidate_budget=1024
                run.active_environment_id=env-a
                run.bootstrap_policies=bootstrap.csv
                run.commit_sha=0000000000000000000000000000000000000000
                run.dirty_working_tree=false
                scenario.required=s1-env-b-src8-core32-r1of4
                scenario.required=s1-env-a-src1-core32-r1of32
                """);
        ClosedLoopConfig config = ClosedLoopConfigCodec.read(configFile);
        assertThat(config.workspace()).isEqualTo(temp.resolve("workspace"));
        assertThat(config.bootstrapPolicies()).contains(temp.resolve("config/bootstrap.csv"));
        assertThat(config.stopFile()).isEqualTo(temp.resolve("workspace/STOP"));
        assertThat(config.schedulerSeed()).isEqualTo(0x6a09e667f3bcc909L);
        assertThat(config.requiredScenarios())
                .containsExactly(SourceScenario.of("env-a", 1, 32), SourceScenario.of("env-b", 8, 32));
        assertThat(config.budgetConfig().explorationWeight()).isEqualTo(68);
        assertThat(config.generationConfig().screenRows()).isEqualTo(2_097_152);
        assertThat(config.trainingConfig().device()).isEqualTo("auto");
    }

    @Test
    void overridesSeedsNestedConfigurationAndReferenceInputs() throws Exception {
        Path configFile = write("full.conf", """
                run.workspace=workspace
                run.training_run_id=full
                run.iterations=1
                run.candidate_budget=32
                run.active_environment_id=env-a
                run.initial_calibration_plan=plan
                run.initial_observation_bundle=bundle-b
                run.initial_observation_bundle=bundle-a
                run.commit_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                run.dirty_working_tree=true
                run.scheduler_seed_hex=ffffffffffffffff
                run.stop_file=custom.stop
                scenario.required=s1-env-a-src1-core8-r1of8
                calibration.reference_override=s1-env-a-src1-core8-r1of8|run-a
                budget.exploration_weight=1
                candidate.cma.enabled=false
                candidate.score_band_weights=1,1,1,1,1,1,1,1,1,1
                benchmark.expected_repetitions=7
                anchors.fixed_fraction=0.03
                calibration.minimum_strong_anchors=4
                calibration.minimum_weak_anchors=2
                aggregation.bootstrap_seed_hex=8000000000000000
                training.split_seed_hex=ffffffffffffffff
                training.model_seed_hex=8000000000000000
                training.device=cpu
                evaluation.maximum_grouped_macro_mae=0.19
                """);
        ClosedLoopConfig config = ClosedLoopConfigCodec.read(configFile);
        assertThat(config.schedulerSeed()).isEqualTo(-1L);
        assertThat(config.aggregationConfig().bootstrapSeed()).isEqualTo(Long.MIN_VALUE);
        assertThat(config.trainingConfig().splitSeed()).isEqualTo(-1L);
        assertThat(config.stopFile()).isEqualTo(temp.resolve("custom.stop"));
        assertThat(config.initialObservationBundles())
                .containsExactly(temp.resolve("bundle-b"), temp.resolve("bundle-a"));
        assertThat(config.referenceOverrides()).containsValue("run-a");
        assertThat(config.benchmarkConfig().expectedRepetitions()).isEqualTo(7);
        assertThat(config.trainingConfig().thresholds().maximumGroupedMacroMae())
                .isEqualTo(.19);
    }

    @Test
    void parsesInitialObservationBundleDirectory() throws Exception {
        Path configFile = write("bundle-directory.conf", """
                run.workspace=workspace
                run.training_run_id=full
                run.iterations=1
                run.candidate_budget=32
                run.active_environment_id=env-a
                run.initial_calibration_plan=plan
                run.initial_observation_bundle_directory=bundles
                run.commit_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                run.dirty_working_tree=true
                scenario.required=s1-env-a-src1-core8-r1of8
                """);
        ClosedLoopConfig config = ClosedLoopConfigCodec.read(configFile);
        assertThat(config.initialObservationBundleDirectory()).contains(temp.resolve("bundles"));
        assertThat(config.initialObservationBundles()).isEmpty();
    }

    @Test
    void rejectsMalformedAndAmbiguousConfiguration() throws Exception {
        assertRejected("unknown=x\n", "Unknown key");
        assertRejected("run.workspace=a\nrun.workspace=b\n", "Duplicate key");
        assertRejected("run.workspace=a", "final LF");
        assertRejected("\ufeffrun.workspace=a\n", "BOM");
        assertRejected("run.workspace=a\r\n", "LF line endings");
        assertRejected(
                minimal("").replace("run.dirty_working_tree=false", "run.dirty_working_tree=TRUE"), "true or false");
        assertRejected(
                minimal("")
                        .replace(
                                "run.bootstrap_policies=boot",
                                "run.bootstrap_policies=boot\n" + "run.scheduler_seed_hex=ABCDEF0123456789"),
                "lower-case hex");
        assertRejected(
                minimal("")
                        .replace(
                                "run.bootstrap_policies=boot",
                                "run.bootstrap_policies=boot\nrun.initial_calibration_plan=plan"),
                "mutually exclusive");
        assertRejected(minimal("scenario.required=s1-env-a-src1-core8-r1of8"), "Duplicate list value");
    }

    @Test
    void allowsMissingBootstrapSourceForGeneratedBootstrapVectors() throws Exception {
        Path configFile = write("generated-bootstrap.conf", minimal("").replace("run.bootstrap_policies=boot\n", ""));

        ClosedLoopConfig config = ClosedLoopConfigCodec.read(configFile);

        assertThat(config.bootstrapPolicies()).isEmpty();
        assertThat(config.initialCalibrationPlan()).isEmpty();
    }

    @Test
    void everyDeclaredKeyMapsToItsExactTypedRecordComponent() throws Exception {
        ClosedLoopConfig config = ClosedLoopConfigCodec.read(write(
                "mapping/full.conf",
                fullConfiguration("run.initial_calibration_plan=plan/../plan\n"
                        + "run.initial_observation_bundle=bundles/b\n"
                        + "run.initial_observation_bundle=bundles/a\n")));
        assertThat(config.workspace()).isEqualTo(temp.resolve("mapping/workspace"));
        assertThat(config.trainingRunId()).isEqualTo("mapped-run");
        assertThat(config.iterations()).isEqualTo(2);
        assertThat(config.candidateBudget()).isEqualTo(64);
        assertThat(config.activeEnvironmentId()).isEqualTo("env-a");
        assertThat(config.scenariosPerIteration()).isEqualTo(3);
        assertThat(config.schedulerSeed()).isEqualTo(-1L);
        assertThat(config.initialSobolCursor()).isEqualTo(456);
        assertThat(config.bootstrapPolicies()).isEmpty();
        assertThat(config.initialCalibrationPlan()).contains(temp.resolve("mapping/plan"));
        assertThat(config.initialObservationBundleDirectory()).isEmpty();
        assertThat(config.initialObservationBundles())
                .containsExactly(temp.resolve("mapping/bundles/b"), temp.resolve("mapping/bundles/a"));
        assertThat(config.commitSha()).isEqualTo("a".repeat(40));
        assertThat(config.dirtyWorkingTree()).isTrue();
        assertThat(config.resume()).isFalse();
        assertThat(config.stopFile()).isEqualTo(temp.resolve("mapping/custom.stop"));
        assertThat(config.requiredScenarios())
                .containsExactly(SourceScenario.of("env-a", 1, 8), SourceScenario.of("env-b", 4, 8));
        assertThat(config.referenceOverrides())
                .containsExactly(Map.entry(SourceScenario.of("env-a", 1, 8), "reference-a"));

        var budget = config.budgetConfig();
        assertThat(budget.explorationWeight()).isEqualTo(11);
        assertThat(budget.carryForwardWeight()).isEqualTo(12);
        assertThat(budget.leaderRevalidationWeight()).isEqualTo(13);
        assertThat(budget.disagreementAuditWeight()).isEqualTo(14);
        var generation = config.generationConfig();
        assertThat(generation.screenRows()).isEqualTo(101);
        assertThat(generation.maximumPredictionRows()).isEqualTo(77);
        assertThat(generation.scoreBandWeights()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        assertThat(generation.cmaWeight()).isEqualTo(3);
        assertThat(generation.scoreBandWeight()).isEqualTo(4);
        assertThat(generation.directSobolWeight()).isEqualTo(5);
        assertThat(generation.cma().enabled()).isFalse();
        assertThat(generation.cma().islands()).isEqualTo(2);
        assertThat(generation.cma().generations()).isEqualTo(3);
        assertThat(generation.cma().populationSize()).isEqualTo(8);
        assertThat(generation.cma().initialSigma()).isEqualTo(.25);
        assertThat(generation.cma().minimumSeedPolicies()).isEqualTo(2);

        var benchmark = config.benchmarkConfig();
        assertThat(benchmark.expectedRepetitions()).isEqualTo(4);
        assertThat(benchmark.sampleDurationNanos()).isEqualTo(11);
        assertThat(benchmark.livenessTimeoutNanos()).isEqualTo(12);
        assertThat(benchmark.framesPerSource()).isEqualTo(13);
        assertThat(benchmark.resetTimeoutNanos()).isEqualTo(14);
        assertThat(benchmark.orderedFrames()).isTrue();
        var anchors = config.anchorSelectionConfig();
        assertThat(anchors.fixedFraction()).isEqualTo(.03);
        assertThat(anchors.minimumFixedAnchors()).isEqualTo(2);
        assertThat(anchors.maximumBootstrapNonSuccessRate()).isEqualTo(.11);
        assertThat(anchors.maximumBootstrapRelativeIqr()).isEqualTo(.33);
        assertThat(anchors.allowImportedBootstrap()).isTrue();
        var calibration = config.calibrationConfig();
        assertThat(calibration.minimumStrongAnchors()).isEqualTo(4);
        assertThat(calibration.minimumWeakAnchors()).isEqualTo(2);
        assertThat(calibration.maximumStrongResidual()).isEqualTo(.04);
        assertThat(calibration.maximumWeakResidual()).isEqualTo(.14);
        assertThat(calibration.minimumLogSigma()).isEqualTo(.02);
        assertThat(calibration.maximumAnchorWeightShare()).isEqualTo(.4);
        var aggregation = config.aggregationConfig();
        assertThat(aggregation.minimumSuccessfulRepetitions()).isEqualTo(2);
        assertThat(aggregation.minimumSuccessFraction()).isEqualTo(.75);
        assertThat(aggregation.bootstrapReplicates()).isEqualTo(99);
        assertThat(aggregation.bootstrapSeed()).isEqualTo(Long.MIN_VALUE);
        assertThat(aggregation.calibrationAcceptance()).isEqualTo(CalibrationAcceptance.INCLUDE_WEAK);

        var training = config.trainingConfig();
        assertThat(training.splitSeed()).isEqualTo(-1L);
        assertThat(training.modelSeed()).isEqualTo(Long.MIN_VALUE);
        assertThat(training.device()).isEqualTo("gpu2");
        assertThat(training.ensembleMembers()).isEqualTo(5);
        assertThat(training.losoEvaluationMembers()).isEqualTo(2);
        assertThat(training.ablationMembers()).isEqualTo(3);
        assertThat(training.maxEpochs()).isEqualTo(9);
        assertThat(training.patience()).isEqualTo(2);
        assertThat(training.batchSize()).isEqualTo(7);
        assertThat(training.learningRate()).isEqualTo(.002f);
        assertThat(training.weightDecay()).isEqualTo(.003f);
        assertThat(training.labelSmoothing()).isEqualTo(.04f);
        assertThat(training.minimumTrainPolicyGroups()).isEqualTo(2);
        assertThat(training.minimumValidationPolicyGroups()).isEqualTo(3);
        assertThat(training.minimumTestPolicyGroups()).isEqualTo(4);
        assertThat(training.minimumTrainRowsPerScenario()).isEqualTo(5);
        assertThat(training.minimumValidationRowsPerScenario()).isEqualTo(6);
        assertThat(training.minimumTestRowsPerScenario()).isEqualTo(7);
        assertThat(training.includeWeakCalibrationRows()).isTrue();
        assertThat(training.featureSelectionMode()).isEqualTo(FeatureSelectionMode.REQUIRE_COUNTS);
        var thresholds = training.thresholds();
        assertThat(thresholds.maximumGroupedMacroMae()).isEqualTo(.11);
        assertThat(thresholds.minimumGroupedMacroSpearman()).isEqualTo(.12);
        assertThat(thresholds.minimumGroupedMacroPrecisionAtTen()).isEqualTo(.13);
        assertThat(thresholds.maximumLosoMacroMae()).isEqualTo(.14);
        assertThat(thresholds.minimumLosoMacroSpearman()).isEqualTo(.15);
        assertThat(thresholds.maximumLosoWorstScenarioMae()).isEqualTo(.16);
        assertThat(thresholds.minimumContextMaeImprovement()).isEqualTo(.17);
        assertThat(thresholds.minimumContextSpearmanImprovement()).isEqualTo(.18);
        assertThat(thresholds.maximumContextMaeRegression()).isEqualTo(.19);
        assertThat(thresholds.maximumContextSpearmanRegression()).isEqualTo(.20);
        assertThat(thresholds.minimumCountsCrossEnvironmentMaeImprovement()).isEqualTo(.21);
        assertThat(thresholds.maximumCountsSpearmanRegression()).isEqualTo(.22);
        assertThat(thresholds.maximumCountsWorstEnvironmentMaeRegression()).isEqualTo(.23);
    }

    @Test
    void rejectsMalformedScalarListPathAndCrossFieldForms() throws Exception {
        Map<String, String> malformed = new LinkedHashMap<>();
        malformed.put(
                "boolean", replaceLine(minimal(""), "run.dirty_working_tree=false", "run.dirty_working_tree=False"));
        malformed.put(
                "enum",
                minimal("")
                        .replace("scenario.required=", "training.feature_selection_mode=unknown\nscenario.required="));
        malformed.put(
                "seed", minimal("").replace("scenario.required=", "run.scheduler_seed_hex=abcdef\nscenario.required="));
        malformed.put("integer-plus", minimal("").replace("run.iterations=1", "run.iterations=+1"));
        malformed.put("integer-decimal", minimal("").replace("run.iterations=1", "run.iterations=1.0"));
        malformed.put(
                "integer-overflow", minimal("").replace("run.iterations=1", "run.iterations=999999999999999999999"));
        malformed.put(
                "decimal-nan",
                minimal("").replace("scenario.required=", "anchors.fixed_fraction=NaN\nscenario.required="));
        malformed.put(
                "decimal-infinity",
                minimal("").replace("scenario.required=", "anchors.fixed_fraction=1e999\nscenario.required="));
        malformed.put("path", minimal("").replace("run.workspace=workspace", "run.workspace=bad\\\\path"));
        malformed.put(
                "list-empty",
                minimal("").replace("scenario.required=", "candidate.score_band_weights=1,2,,4\nscenario.required="));
        malformed.put(
                "list-width",
                minimal("").replace("scenario.required=", "candidate.score_band_weights=1,2\nscenario.required="));
        malformed.put(
                "list-token",
                minimal("")
                        .replace(
                                "scenario.required=",
                                "candidate.score_band_weights=1,2,3,4,5,6,7,8,9,x\n" + "scenario.required="));
        malformed.put(
                "alternatives",
                minimal("")
                        .replace(
                                "run.bootstrap_policies=boot",
                                "run.bootstrap_policies=boot\nrun.initial_calibration_plan=plan"));
        malformed.put(
                "bundle-without-plan",
                minimal("").replace("scenario.required=", "run.initial_observation_bundle=bundle\nscenario.required="));
        malformed.put(
                "bundle-directory-without-plan",
                minimal("")
                        .replace(
                                "scenario.required=",
                                "run.initial_observation_bundle_directory=bundles\nscenario.required="));
        malformed.put(
                "active-environment",
                minimal("").replace("run.active_environment_id=env-a", "run.active_environment_id=env-b"));
        malformed.put("run-id", minimal("").replace("run.training_run_id=test", "run.training_run_id=BAD"));
        malformed.put("zero-iterations", minimal("").replace("run.iterations=1", "run.iterations=0"));
        malformed.put("zero-budget-weights", minimal("").replace("scenario.required=", """
                budget.exploration_weight=0
                budget.carry_forward_weight=0
                budget.leader_revalidation_weight=0
                budget.disagreement_audit_weight=0
                scenario.required="""));
        malformed.put(
                "cma",
                minimal("").replace("scenario.required=", "candidate.cma.population_size=7\nscenario.required="));
        malformed.put(
                "benchmark",
                minimal("").replace("scenario.required=", "benchmark.expected_repetitions=0\nscenario.required="));
        malformed.put(
                "anchors", minimal("").replace("scenario.required=", "anchors.fixed_fraction=0\nscenario.required="));
        malformed.put(
                "calibration",
                minimal("")
                        .replace(
                                "scenario.required=",
                                "calibration.minimum_strong_anchors=1\n"
                                        + "calibration.minimum_weak_anchors=2\nscenario.required="));
        malformed.put(
                "aggregation",
                minimal("")
                        .replace("scenario.required=", "aggregation.minimum_success_fraction=0\nscenario.required="));
        malformed.put(
                "training",
                minimal("").replace("scenario.required=", "training.ensemble_members=4\nscenario.required="));
        malformed.put(
                "threshold",
                minimal("")
                        .replace("scenario.required=", "evaluation.maximum_grouped_macro_mae=2\nscenario.required="));
        malformed.put(
                "override-outside-catalog",
                minimal("")
                        .replace(
                                "scenario.required=",
                                "calibration.reference_override=s1-env-b-src1-core8-r1of8|run\n"
                                        + "scenario.required="));
        malformed.put("normalized-path-duplicate", minimal("").replace("run.bootstrap_policies=boot", """
                run.initial_calibration_plan=plan
                run.initial_observation_bundle=a/../bundle
                run.initial_observation_bundle=bundle"""));
        for (Map.Entry<String, String> entry : malformed.entrySet()) {
            Path file = write("malformed/" + entry.getKey() + ".conf", entry.getValue());
            assertThatThrownBy(() -> ClosedLoopConfigCodec.read(file))
                    .as(entry.getKey())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void fingerprintIncludesEveryFrozenScalarAndExcludesOperationalKeys() throws Exception {
        Files.createDirectories(temp.resolve("fingerprint"));
        Files.writeString(temp.resolve("fingerprint/boot-a"), "a\n");
        Files.writeString(temp.resolve("fingerprint/boot-b"), "b\n");
        String base = fullConfiguration("run.bootstrap_policies=boot-a\n");
        Path basePath = write("fingerprint/base.conf", base);
        String baseline = ClosedLoopConfigFingerprint.sha256(ClosedLoopConfigCodec.read(basePath));

        Map<String, String> frozen = fingerprintAlternatives();
        for (Map.Entry<String, String> entry : frozen.entrySet()) {
            String changed = replaceValue(base, entry.getKey(), entry.getValue());
            Path file = write("fingerprint/frozen-" + safe(entry.getKey()) + ".conf", changed);
            assertThat(ClosedLoopConfigFingerprint.sha256(ClosedLoopConfigCodec.read(file)))
                    .as(entry.getKey())
                    .isNotEqualTo(baseline);
        }

        Map<String, String> operational = Map.of(
                "run.workspace", "other-workspace",
                "run.active_environment_id", "env-b",
                "run.resume", "true",
                "run.stop_file", "other.stop");
        for (Map.Entry<String, String> entry : operational.entrySet()) {
            Path file = write(
                    "fingerprint/operational-" + safe(entry.getKey()) + ".conf",
                    replaceValue(base, entry.getKey(), entry.getValue()));
            assertThat(ClosedLoopConfigFingerprint.sha256(ClosedLoopConfigCodec.read(file)))
                    .as(entry.getKey())
                    .isEqualTo(baseline);
        }

        String before = System.getProperty("euhedral.training.device");
        System.setProperty("euhedral.training.device", "gpu99");
        try {
            assertThat(ClosedLoopConfigFingerprint.sha256(ClosedLoopConfigCodec.read(basePath)))
                    .isEqualTo(baseline);
        } finally {
            if (before == null) {
                System.clearProperty("euhedral.training.device");
            } else {
                System.setProperty("euhedral.training.device", before);
            }
        }
    }

    private static String fullConfiguration(String bootstrapSource) {
        return """
                run.workspace=workspace
                run.training_run_id=mapped-run
                run.iterations=2
                run.candidate_budget=64
                run.active_environment_id=env-a
                run.scenarios_per_iteration=3
                run.scheduler_seed_hex=ffffffffffffffff
                run.initial_sobol_cursor=456
                %srun.commit_sha=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                run.dirty_working_tree=true
                run.resume=false
                run.stop_file=custom.stop
                scenario.required=s1-env-b-src4-core8-r1of2
                scenario.required=s1-env-a-src1-core8-r1of8
                calibration.reference_override=s1-env-a-src1-core8-r1of8|reference-a
                budget.exploration_weight=11
                budget.carry_forward_weight=12
                budget.leader_revalidation_weight=13
                budget.disagreement_audit_weight=14
                candidate.screen_rows=101
                candidate.maximum_prediction_rows=77
                candidate.score_band_weights=1,2,3,4,5,6,7,8,9,10
                candidate.cma_weight=3
                candidate.score_band_weight=4
                candidate.direct_sobol_weight=5
                candidate.cma.enabled=false
                candidate.cma.islands=2
                candidate.cma.generations=3
                candidate.cma.population_size=8
                candidate.cma.initial_sigma=0.25
                candidate.cma.minimum_seed_policies=2
                benchmark.expected_repetitions=4
                benchmark.sample_duration_nanos=11
                benchmark.liveness_timeout_nanos=12
                benchmark.frames_per_source=13
                benchmark.reset_timeout_nanos=14
                benchmark.ordered_frames=true
                anchors.fixed_fraction=0.03
                anchors.minimum_fixed_anchors=2
                anchors.maximum_bootstrap_non_success_rate=0.11
                anchors.maximum_bootstrap_relative_iqr=0.33
                anchors.allow_imported_bootstrap=true
                calibration.minimum_strong_anchors=4
                calibration.minimum_weak_anchors=2
                calibration.maximum_strong_residual=0.04
                calibration.maximum_weak_residual=0.14
                calibration.minimum_log_sigma=0.02
                calibration.maximum_anchor_weight_share=0.4
                aggregation.minimum_successful_repetitions=2
                aggregation.minimum_success_fraction=0.75
                aggregation.bootstrap_replicates=99
                aggregation.bootstrap_seed_hex=8000000000000000
                aggregation.calibration_acceptance=INCLUDE_WEAK
                training.split_seed_hex=ffffffffffffffff
                training.model_seed_hex=8000000000000000
                training.device=GPU2
                training.ensemble_members=5
                training.loso_evaluation_members=2
                training.ablation_members=3
                training.max_epochs=9
                training.patience=2
                training.batch_size=7
                training.learning_rate=0.002
                training.weight_decay=0.003
                training.label_smoothing=0.04
                training.minimum_train_policy_groups=2
                training.minimum_validation_policy_groups=3
                training.minimum_test_policy_groups=4
                training.minimum_train_rows_per_scenario=5
                training.minimum_validation_rows_per_scenario=6
                training.minimum_test_rows_per_scenario=7
                training.include_weak_calibration_rows=true
                training.feature_selection_mode=REQUIRE_COUNTS
                evaluation.maximum_grouped_macro_mae=0.11
                evaluation.minimum_grouped_macro_spearman=0.12
                evaluation.minimum_grouped_macro_precision_at_ten=0.13
                evaluation.maximum_loso_macro_mae=0.14
                evaluation.minimum_loso_macro_spearman=0.15
                evaluation.maximum_loso_worst_scenario_mae=0.16
                evaluation.minimum_context_mae_improvement=0.17
                evaluation.minimum_context_spearman_improvement=0.18
                evaluation.maximum_context_mae_regression=0.19
                evaluation.maximum_context_spearman_regression=0.20
                evaluation.minimum_counts_cross_environment_mae_improvement=0.21
                evaluation.maximum_counts_spearman_regression=0.22
                evaluation.maximum_counts_worst_environment_mae_regression=0.23
                """.formatted(bootstrapSource);
    }

    private static Map<String, String> fingerprintAlternatives() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put("run.training_run_id", "mapped-run-2");
        result.put("run.iterations", "3");
        result.put("run.candidate_budget", "65");
        result.put("run.scenarios_per_iteration", "2");
        result.put("run.scheduler_seed_hex", "7fffffffffffffff");
        result.put("run.initial_sobol_cursor", "457");
        result.put("run.bootstrap_policies", "boot-b");
        result.put("run.commit_sha", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        result.put("run.dirty_working_tree", "false");
        result.put("scenario.required", "s1-env-a-src2-core8-r1of4");
        result.put("calibration.reference_override", "s1-env-a-src1-core8-r1of8|reference-b");
        result.put("budget.exploration_weight", "15");
        result.put("budget.carry_forward_weight", "16");
        result.put("budget.leader_revalidation_weight", "17");
        result.put("budget.disagreement_audit_weight", "18");
        result.put("candidate.screen_rows", "102");
        result.put("candidate.maximum_prediction_rows", "78");
        result.put("candidate.score_band_weights", "2,2,3,4,5,6,7,8,9,10");
        result.put("candidate.cma_weight", "6");
        result.put("candidate.score_band_weight", "7");
        result.put("candidate.direct_sobol_weight", "8");
        result.put("candidate.cma.enabled", "true");
        result.put("candidate.cma.islands", "3");
        result.put("candidate.cma.generations", "4");
        result.put("candidate.cma.population_size", "9");
        result.put("candidate.cma.initial_sigma", "0.26");
        result.put("candidate.cma.minimum_seed_policies", "3");
        result.put("benchmark.expected_repetitions", "5");
        result.put("benchmark.sample_duration_nanos", "21");
        result.put("benchmark.liveness_timeout_nanos", "22");
        result.put("benchmark.frames_per_source", "23");
        result.put("benchmark.reset_timeout_nanos", "24");
        result.put("benchmark.ordered_frames", "false");
        result.put("anchors.fixed_fraction", "0.04");
        result.put("anchors.minimum_fixed_anchors", "3");
        result.put("anchors.maximum_bootstrap_non_success_rate", "0.12");
        result.put("anchors.maximum_bootstrap_relative_iqr", "0.34");
        result.put("anchors.allow_imported_bootstrap", "false");
        result.put("calibration.minimum_strong_anchors", "5");
        result.put("calibration.minimum_weak_anchors", "3");
        result.put("calibration.maximum_strong_residual", "0.05");
        result.put("calibration.maximum_weak_residual", "0.15");
        result.put("calibration.minimum_log_sigma", "0.03");
        result.put("calibration.maximum_anchor_weight_share", "0.41");
        result.put("aggregation.minimum_successful_repetitions", "3");
        result.put("aggregation.minimum_success_fraction", "0.76");
        result.put("aggregation.bootstrap_replicates", "100");
        result.put("aggregation.bootstrap_seed_hex", "8000000000000001");
        result.put("aggregation.calibration_acceptance", "STRONG_ONLY");
        result.put("training.split_seed_hex", "fffffffffffffffe");
        result.put("training.model_seed_hex", "8000000000000001");
        result.put("training.device", "cpu");
        result.put("training.ensemble_members", "7");
        result.put("training.loso_evaluation_members", "3");
        result.put("training.ablation_members", "5");
        result.put("training.max_epochs", "10");
        result.put("training.patience", "3");
        result.put("training.batch_size", "8");
        result.put("training.learning_rate", "0.0021");
        result.put("training.weight_decay", "0.0031");
        result.put("training.label_smoothing", "0.041");
        result.put("training.minimum_train_policy_groups", "3");
        result.put("training.minimum_validation_policy_groups", "4");
        result.put("training.minimum_test_policy_groups", "5");
        result.put("training.minimum_train_rows_per_scenario", "6");
        result.put("training.minimum_validation_rows_per_scenario", "7");
        result.put("training.minimum_test_rows_per_scenario", "8");
        result.put("training.include_weak_calibration_rows", "false");
        result.put("training.feature_selection_mode", "AUTO_COUNTS_IF_VALIDATED");
        result.put("evaluation.maximum_grouped_macro_mae", "0.24");
        result.put("evaluation.minimum_grouped_macro_spearman", "0.25");
        result.put("evaluation.minimum_grouped_macro_precision_at_ten", "0.26");
        result.put("evaluation.maximum_loso_macro_mae", "0.27");
        result.put("evaluation.minimum_loso_macro_spearman", "0.28");
        result.put("evaluation.maximum_loso_worst_scenario_mae", "0.29");
        result.put("evaluation.minimum_context_mae_improvement", "0.30");
        result.put("evaluation.minimum_context_spearman_improvement", "0.31");
        result.put("evaluation.maximum_context_mae_regression", "0.32");
        result.put("evaluation.maximum_context_spearman_regression", "0.33");
        result.put("evaluation.minimum_counts_cross_environment_mae_improvement", "0.34");
        result.put("evaluation.maximum_counts_spearman_regression", "0.35");
        result.put("evaluation.maximum_counts_worst_environment_mae_regression", "0.36");
        return Map.copyOf(result);
    }

    private static String replaceValue(String text, String key, String value) {
        String changed = text.replaceFirst(
                "(?m)^" + java.util.regex.Pattern.quote(key) + "=[^\\n]*$",
                java.util.regex.Matcher.quoteReplacement(key + "=" + value));
        if (key.equals("scenario.required")) {
            changed = changed.replace(
                    "calibration.reference_override=s1-env-a-src1-core8-r1of8|reference-a",
                    "calibration.reference_override=s1-env-a-src2-core8-r1of4|reference-a");
        }
        return changed;
    }

    private static String replaceLine(String text, String original, String replacement) {
        return text.replace(original, replacement);
    }

    private static String safe(String key) {
        return key.replace('.', '-');
    }

    private String minimal(String extra) {
        return """
                run.workspace=workspace
                run.training_run_id=test
                run.iterations=1
                run.candidate_budget=32
                run.active_environment_id=env-a
                run.bootstrap_policies=boot
                run.commit_sha=0000000000000000000000000000000000000000
                run.dirty_working_tree=false
                scenario.required=s1-env-a-src1-core8-r1of8
                """ + (extra.isEmpty() ? "" : extra + "\n");
    }

    private void assertRejected(String text, String message) throws Exception {
        Path file = write("bad-" + Math.abs(text.hashCode()) + ".conf", text);
        assertThatThrownBy(() -> ClosedLoopConfigCodec.read(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }

    private Path write(String relative, String contents) throws Exception {
        Path file = temp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }
}
