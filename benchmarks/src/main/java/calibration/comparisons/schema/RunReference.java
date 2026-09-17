package calibration.comparisons.schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Identifies one completed calibration run invocation.
public record RunReference(@NonNull String path) {

    @JsonCreator
    public RunReference(@JsonProperty("path") @NonNull String path) {
        Objects.requireNonNull(path, "path must not be null");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        this.path = path;
    }

    @JsonCreator
    public static RunReference fromString(String path) {
        return RunReference.of(path);
    }

    public static RunReference of(@NonNull String path) {
        return new RunReference(path);
    }
}
