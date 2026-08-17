package calibration.io.exceptions;

import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Thrown when an expected authoritative FORK scope summary is missing from calibration telemetry.
public class MissingAuthoritativeSummaryException extends RunLoadException {
    private final Path artifactPath;

    public MissingAuthoritativeSummaryException(@NonNull Path runPath, @NonNull Path artifactPath) {
        super(runPath, "Missing authoritative FORK scope summary in " + artifactPath + " in run " + runPath);
        this.artifactPath = Objects.requireNonNull(artifactPath, "artifactPath must not be null");
    }

    public Path artifactPath() {
        return artifactPath;
    }
}
