package io.euhedral_execution.training.packaging.data;

import io.euhedral_execution.training.packaging.enums.TrainingRunPackageStatus;
import java.nio.file.Path;

public record TrainingRunPackage(Path directory, Path manifest, String packageId,
        TrainingRunPackageStatus status) {
}
