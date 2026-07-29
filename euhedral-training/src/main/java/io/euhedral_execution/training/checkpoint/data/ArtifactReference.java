package io.euhedral_execution.training.checkpoint.data;

public record ArtifactReference(String relativePath, String sha256) {
    public ArtifactReference {
        if (relativePath == null || relativePath.isBlank() || relativePath.indexOf('\\') >= 0
                || PathValidator.invalid(relativePath) || sha256 == null
                || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid artifact reference");
        }
    }

    static final class PathValidator {
        static boolean invalid(String value) {
            java.nio.file.Path path = java.nio.file.Path.of(value);
            if (path.isAbsolute()) {
                return true;
            }
            for (java.nio.file.Path segment : path) {
                if (segment.toString().isEmpty() || segment.toString().equals(".")
                        || segment.toString().equals("..")) {
                    return true;
                }
            }
            return false;
        }
    }
}
