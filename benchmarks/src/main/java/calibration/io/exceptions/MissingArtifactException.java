package calibration.io.exceptions;

import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Thrown when a required artifact file is missing from a completed run directory.
public class MissingArtifactException extends RunLoadException {
    private final Path artifactPath;

    public MissingArtifactException(@NonNull Path runPath, @NonNull Path artifactPath) {
        super(runPath, "Required artifact missing: " + artifactPath + " in run " + runPath);
        this.artifactPath = Objects.requireNonNull(artifactPath, "artifactPath must not be null");
    }

    public Path artifactPath() {
        return artifactPath;
    }
}
