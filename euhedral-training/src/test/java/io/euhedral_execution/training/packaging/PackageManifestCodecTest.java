package io.euhedral_execution.training.packaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.checkpoint.enums.CheckpointStage;
import io.euhedral_execution.training.data.SourceScenario;
import io.euhedral_execution.training.packaging.enums.TrainingRunPackageStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackageManifestCodecTest {
    @TempDir
    Path temp;

    @Test
    void canonicalRoundTripIsByteStable() throws Exception {
        var manifest = new TrainingRunManifest(
                "run.partial.r00000001",
                "run",
                1,
                CheckpointStage.BOOTSTRAP_PENDING,
                TrainingRunPackageStatus.PARTIAL_RECOVERABLE,
                false,
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(40),
                false,
                List.of(SourceScenario.of("env", 1, 4)),
                null,
                List.of(),
                List.of(),
                List.of(
                        new PackageOmission("MERGE", "NOT_YET_CALIBRATED", true),
                        new PackageOmission("MODEL", "NOT_YET_TRAINED", true),
                        new PackageOmission("SCHEDULE", "NO_NORMAL_ITERATION_SCHEDULE_AT_CHECKPOINT", true)));
        String encoded = PackageManifestCodec.encode(manifest);
        Path path = temp.resolve("manifest.json");
        Files.writeString(path, encoded);
        assertThat(PackageManifestCodec.read(path)).isEqualTo(manifest);
        assertThat(PackageManifestCodec.encode(PackageManifestCodec.read(path))).isEqualTo(encoded);
    }

    @Test
    void rejectsDuplicateKeysEvenWhenFirstValueIsNull() throws Exception {
        Path path = temp.resolve("manifest.json");
        Files.writeString(path, "{\"artifact_type\":null,\"artifact_type\":null}\n");
        assertThatThrownBy(() -> PackageManifestCodec.read(path)).isInstanceOf(java.io.IOException.class);
    }
}
