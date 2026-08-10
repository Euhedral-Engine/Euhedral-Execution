package io.euhedral_execution.training.packaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.benchmark.config.BenchmarkExecutionConfig;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.packaging.config.TrainingRunPackageInputs;
import io.euhedral_execution.training.packaging.io.TrainingRunPackageInputsCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrainingRunPackageInputsCodecTest {
    @TempDir
    Path temp;

    @Test
    void canonicalRoundTripPreservesRawSchedulerSeed() throws Exception {
        var inputs = new TrainingRunPackageInputs(
                "run.partial.r00000001",
                "run",
                1,
                -1L,
                "a".repeat(40),
                true,
                BenchmarkExecutionConfig.defaults(),
                new TreeSet<>(java.util.List.of(SourceScenario.of("env", 3, 8))));
        Path path = temp.resolve("inputs.properties");
        Files.writeString(path, TrainingRunPackageInputsCodec.encode(inputs));
        assertThat(TrainingRunPackageInputsCodec.read(path)).isEqualTo(inputs);
    }

    @Test
    void rejectsCarriageReturnsAndOutOfOrderKeys() throws Exception {
        Path path = temp.resolve("bad.properties");
        Files.writeString(path, "artifact_type=x\r\nschema_version=1\r\n");
        assertThatThrownBy(() -> TrainingRunPackageInputsCodec.read(path)).isInstanceOf(java.io.IOException.class);
    }
}
