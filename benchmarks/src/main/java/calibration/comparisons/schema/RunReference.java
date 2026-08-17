package calibration.comparisons.schema;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Identifies one completed calibration run invocation.
public record RunReference(@NonNull String path, @Nullable String label) {

    public RunReference {
        Objects.requireNonNull(path, "path must not be null");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
    }

    public static RunReference of(@NonNull String path) {
        return new RunReference(path, null);
    }

    public static RunReference of(@NonNull String path, @Nullable String label) {
        return new RunReference(path, label);
    }
}
