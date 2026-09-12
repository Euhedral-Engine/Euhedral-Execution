package calibration.io.exceptions;

import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Thrown when an artifact file exists but cannot be parsed or is structurally invalid.
public class MalformedArtifactException extends RunLoadException {
    private final Path artifactPath;

    public MalformedArtifactException(@NonNull Path runPath, @NonNull Path artifactPath, @NonNull String reason) {
        super(runPath, "Malformed artifact " + artifactPath + " in run " + runPath + ": " + reason);
        this.artifactPath = Objects.requireNonNull(artifactPath, "artifactPath must not be null");
    }

    public MalformedArtifactException(
            @NonNull Path runPath, @NonNull Path artifactPath, @NonNull String reason, Throwable cause) {
        super(runPath, "Malformed artifact " + artifactPath + " in run " + runPath + ": " + reason, cause);
        this.artifactPath = Objects.requireNonNull(artifactPath, "artifactPath must not be null");
    }

    public Path artifactPath() {
        return artifactPath;
    }
}
