package calibration.io.exceptions;

import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Base exception for failures during completed calibration run loading.
public class RunLoadException extends RuntimeException {
    private final Path runPath;

    public RunLoadException(@NonNull Path runPath, @NonNull String message) {
        super(message);
        this.runPath = Objects.requireNonNull(runPath, "runPath must not be null");
    }

    public RunLoadException(@NonNull Path runPath, @NonNull String message, Throwable cause) {
        super(message, cause);
        this.runPath = Objects.requireNonNull(runPath, "runPath must not be null");
    }

    public Path runPath() {
        return runPath;
    }
}
