package io.euhedral_execution.training.packaging;

import java.nio.file.Path;
import java.util.Objects;

public record TrainingRunPackageRequest(Path workspace, Path outputRoot,
        TrainingRunPackageInputs inputs) {
    public TrainingRunPackageRequest {
        Objects.requireNonNull(workspace);
        Objects.requireNonNull(outputRoot);
        Objects.requireNonNull(inputs);
        workspace = workspace.toAbsolutePath().normalize();
        outputRoot = outputRoot.toAbsolutePath().normalize();
    }
}
