package io.euhedral_execution.training.importer.currentworkspace;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

public record CurrentWorkspaceImportRequest(
        Path sourceRoot,
        Path outputDirectory,
        int bootstrapPolicyCount) {
    public CurrentWorkspaceImportRequest {
        Objects.requireNonNull(sourceRoot, "sourceRoot");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        sourceRoot = sourceRoot.toAbsolutePath().normalize();
        outputDirectory = outputDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(sourceRoot)) {
            throw new IllegalArgumentException("Source root must be an existing directory");
        }
        if (bootstrapPolicyCount <= 0) {
            throw new IllegalArgumentException("Bootstrap count must be positive");
        }
        Path input = sourceRoot.resolve("euhedral-training/input").normalize();
        Path output = sourceRoot.resolve("euhedral-training/output").normalize();
        if (outputDirectory.startsWith(input) || outputDirectory.startsWith(output)) {
            throw new IllegalArgumentException("Import output must be outside scanned trees");
        }
        if (Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Import output already exists");
        }
    }
}
