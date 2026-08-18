package calibration.comparisons.schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Identifies one completed calibration run invocation.
public record RunReference(@NonNull String path, @Nullable String label) {

    @JsonCreator
    public RunReference(@JsonProperty("path") @NonNull String path, @JsonProperty("label") @Nullable String label) {
        Objects.requireNonNull(path, "path must not be null");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        this.path = path;
        this.label = label;
    }

    @JsonCreator
    public static RunReference fromString(String path) {
        return RunReference.of(path);
    }

    public static RunReference of(@NonNull String path) {
        return new RunReference(path, null);
    }

    public static RunReference of(@NonNull String path, @Nullable String label) {
        return new RunReference(path, label);
    }
}
