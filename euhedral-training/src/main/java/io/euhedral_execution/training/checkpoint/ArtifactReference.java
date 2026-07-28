package io.euhedral_execution.training.checkpoint;

public record ArtifactReference(String relativePath, String sha256) {
    public ArtifactReference {
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("..")
                || relativePath.startsWith("/") || sha256 == null
                || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid artifact reference");
        }
    }
}
