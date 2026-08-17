package calibration.io.exceptions;

import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Thrown when an artifact file SHA-256 checksum does not match its expected checksum.
public class ChecksumMismatchException extends RunLoadException {
    private final Path artifactPath;
    private final String expectedChecksum;
    private final String actualChecksum;

    public ChecksumMismatchException(
            @NonNull Path runPath,
            @NonNull Path artifactPath,
            @NonNull String expectedChecksum,
            @NonNull String actualChecksum) {
        super(
                runPath,
                "Checksum mismatch for artifact " + artifactPath + " in run " + runPath + ": expected "
                        + expectedChecksum + " but got " + actualChecksum);
        this.artifactPath = Objects.requireNonNull(artifactPath, "artifactPath must not be null");
        this.expectedChecksum = Objects.requireNonNull(expectedChecksum, "expectedChecksum must not be null");
        this.actualChecksum = Objects.requireNonNull(actualChecksum, "actualChecksum must not be null");
    }

    public Path artifactPath() {
        return artifactPath;
    }

    public String expectedChecksum() {
        return expectedChecksum;
    }

    public String actualChecksum() {
        return actualChecksum;
    }
}
