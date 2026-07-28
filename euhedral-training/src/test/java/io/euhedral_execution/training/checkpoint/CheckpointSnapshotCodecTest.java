package io.euhedral_execution.training.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.euhedral_execution.training.scheduling.RotationGroup;
import io.euhedral_execution.training.scheduling.fixtures.Phase3Fixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointSnapshotCodecTest {
    @TempDir
    Path temp;

    @Test
    void roundTripsAllBootstrapStateAndRejectsHighestCorruption() throws Exception {
        TreeMap<RotationGroup, Integer> cursors = new TreeMap<>();
        cursors.put(new RotationGroup("env-a", 4), 0);
        ClosedLoopCheckpoint checkpoint = new ClosedLoopCheckpoint(1, "training", 1,
                CheckpointStage.BOOTSTRAP_PENDING, 1, 131_072, "a".repeat(64),
                Phase3Fixtures.SCENARIOS, cursors, List.of(), List.of(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of());
        LoadedCheckpoint written = CheckpointSnapshotCodec.writeNext(temp, checkpoint);
        assertThat(CheckpointSnapshotCodec.loadLatest(temp, "training", "a".repeat(64))
                .orElseThrow().checkpoint()).isEqualTo(checkpoint);

        Files.writeString(written.snapshotDirectory().resolve("rotation-cursors.csv"),
                "corrupt\n");
        assertThatThrownBy(() -> CheckpointSnapshotCodec.loadLatest(temp, "training",
                "a".repeat(64))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void directoryFingerprintIsOrderIndependentAndContentSensitive() throws Exception {
        Path first = temp.resolve("first");
        Path second = temp.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(first.resolve("a"), "one");
        Files.writeString(first.resolve("b"), "two");
        Files.writeString(second.resolve("b"), "two");
        Files.writeString(second.resolve("a"), "one");
        assertThat(ArtifactFingerprint.sha256(first))
                .isEqualTo(ArtifactFingerprint.sha256(second));
        Files.writeString(second.resolve("a"), "changed");
        assertThat(ArtifactFingerprint.sha256(first))
                .isNotEqualTo(ArtifactFingerprint.sha256(second));
    }
}
