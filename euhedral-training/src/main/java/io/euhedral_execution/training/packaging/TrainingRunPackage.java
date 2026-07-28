package io.euhedral_execution.training.packaging;

import java.nio.file.Path;

public record TrainingRunPackage(Path directory, Path manifest, String packageId,
        TrainingRunPackageStatus status) {
}
