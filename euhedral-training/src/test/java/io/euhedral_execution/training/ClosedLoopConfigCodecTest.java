package io.euhedral_execution.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.data.SourceScenario;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        assertThat(config.bootstrapPolicies()).contains(
                temp.resolve("config/bootstrap.csv"));
        assertThat(config.stopFile()).isEqualTo(temp.resolve("workspace/STOP"));
        assertThat(config.schedulerSeed()).isEqualTo(0x6a09e667f3bcc909L);
        assertThat(config.requiredScenarios()).containsExactly(
                SourceScenario.of("env-a", 1, 32),
                SourceScenario.of("env-b", 8, 32));
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
        assertThat(config.initialObservationBundles()).containsExactly(
                temp.resolve("bundle-b"), temp.resolve("bundle-a"));
        assertThat(config.referenceOverrides()).containsValue("run-a");
        assertThat(config.benchmarkConfig().expectedRepetitions()).isEqualTo(7);
        assertThat(config.trainingConfig().thresholds().maximumGroupedMacroMae())
                .isEqualTo(.19);
    }

    @Test
    void rejectsMalformedAndAmbiguousConfiguration() throws Exception {
        assertRejected("unknown=x\n", "Unknown key");
        assertRejected("run.workspace=a\nrun.workspace=b\n", "Duplicate key");
        assertRejected("run.workspace=a", "final LF");
        assertRejected("\ufeffrun.workspace=a\n", "BOM");
        assertRejected("run.workspace=a\r\n", "LF line endings");
        assertRejected(minimal("").replace("run.dirty_working_tree=false",
                "run.dirty_working_tree=TRUE"), "true or false");
        assertRejected(minimal("").replace("run.bootstrap_policies=boot",
                "run.bootstrap_policies=boot\n"
                        + "run.scheduler_seed_hex=ABCDEF0123456789"),
                "lower-case hex");
        assertRejected(minimal("").replace("run.bootstrap_policies=boot",
                "run.bootstrap_policies=boot\nrun.initial_calibration_plan=plan"),
                "Exactly one bootstrap");
        assertRejected(minimal("").replace("run.bootstrap_policies=boot\n", ""),
                "Exactly one bootstrap");
        assertRejected(minimal("scenario.required=s1-env-a-src1-core8-r1of8"),
                "Duplicate list value");
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
