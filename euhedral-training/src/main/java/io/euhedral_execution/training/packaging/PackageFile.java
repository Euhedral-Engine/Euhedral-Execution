package io.euhedral_execution.training.packaging;

import java.util.List;
import java.util.Objects;

record PackageFile(String path, ArtifactSemanticType semanticType, String mediaType,
        Integer schemaVersion, Long rowCount, String sha256, ProducingStage producingStage,
        List<String> sourceRunIds, ArtifactOrigin origin, boolean complete) {
    PackageFile {
        Objects.requireNonNull(path);
        Objects.requireNonNull(semanticType);
        Objects.requireNonNull(mediaType);
        Objects.requireNonNull(sha256);
        Objects.requireNonNull(producingStage);
        Objects.requireNonNull(sourceRunIds);
        Objects.requireNonNull(origin);
        sourceRunIds = List.copyOf(sourceRunIds);
        if (!path.matches("[^/\\\\]+(?:/[^/\\\\]+)*")
                || java.util.Arrays.stream(path.split("/")).anyMatch(segment ->
                segment.equals(".") || segment.equals(".."))
                || !sha256.matches("[0-9a-f]{64}")
                || schemaVersion != null && schemaVersion < 0
                || rowCount != null && rowCount < 0
                || !sourceRunIds.equals(sourceRunIds.stream().sorted().toList())
                || sourceRunIds.stream().distinct().count() != sourceRunIds.size()) {
            throw new IllegalArgumentException("Invalid package file");
        }
    }
}
