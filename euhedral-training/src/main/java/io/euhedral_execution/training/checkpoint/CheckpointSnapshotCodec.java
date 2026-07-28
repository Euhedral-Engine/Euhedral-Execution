package io.euhedral_execution.training.checkpoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class CheckpointSnapshotCodec {
    public static Optional<LoadedCheckpoint> loadLatest(Path workspace,
            String expectedTrainingRunId, String expectedConfigSha256) throws IOException {
        Path checkpoints = workspace.resolve("checkpoints");
        if (!Files.isDirectory(checkpoints)) {
            return Optional.empty();
        }
        try (var stream = Files.list(checkpoints)) {
            return stream.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("COMPLETE")))
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(path -> new LoadedCheckpoint(path, read(path)))
                    .filter(loaded -> loaded.checkpoint().trainingRunId()
                            .equals(expectedTrainingRunId))
                    .filter(loaded -> loaded.checkpoint().configSha256()
                            .equals(expectedConfigSha256))
                    .findFirst();
        }
    }

    public static LoadedCheckpoint writeNext(Path workspace, ClosedLoopCheckpoint checkpoint)
            throws IOException {
        Path checkpoints = workspace.resolve("checkpoints");
        Files.createDirectories(checkpoints);
        Path target = checkpoints.resolve("checkpoint-%08d".formatted(checkpoint.revision()));
        Path temp = checkpoints.resolve(".checkpoint-%08d.tmp".formatted(checkpoint.revision()));
        Files.createDirectories(temp);
        Files.writeString(temp.resolve("state.csv"), state(checkpoint), StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("required-scenarios.csv"), "schema_version,scenario_id\n",
                StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("rotation-cursors.csv"), "schema_version,group,next_index\n",
                StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("evidence-index.csv"), "schema_version,benchmark_run_id\n",
                StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("carry-forward.csv"), "schema_version,policy_id\n",
                StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("carry-forward-scenarios.csv"),
                "schema_version,policy_id,scenario_id\n", StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("pending-runs.csv"), "schema_version,benchmark_run_id\n",
                StandardCharsets.UTF_8);
        Files.write(temp.resolve("COMPLETE"), new byte[0]);
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        return new LoadedCheckpoint(target, checkpoint);
    }

    private static ClosedLoopCheckpoint read(Path path) {
        try {
            String[] row = Files.readString(path.resolve("state.csv")).split("\n")[1].split(",");
            return new ClosedLoopCheckpoint(1, row[0], Integer.parseInt(row[1]),
                    CheckpointStage.valueOf(row[2]), Integer.parseInt(row[3]),
                    Long.parseLong(row[4]), row[5], new java.util.TreeSet<>(),
                    new java.util.TreeMap<>(), java.util.List.of(), java.util.List.of(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), java.util.List.of());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String state(ClosedLoopCheckpoint checkpoint) {
        return "training_run_id,revision,stage,next_iteration,sobol_cursor,config_sha256\n"
                + String.join(",", checkpoint.trainingRunId(),
                Integer.toString(checkpoint.revision()), checkpoint.stage().name(),
                Integer.toString(checkpoint.nextIteration()), Long.toString(checkpoint.sobolCursor()),
                checkpoint.configSha256()) + "\n";
    }

    private CheckpointSnapshotCodec() {
    }
}
