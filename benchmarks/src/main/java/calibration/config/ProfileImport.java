package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Import declaration for referencing an external profile library.
public record ProfileImport(@NonNull String path, @NonNull String namespace) {

    /// Creates and validates a ProfileImport instance.
    ///
    /// @throws IllegalArgumentException if path or namespace is blank, or if namespace contains '.'
    /// @throws NullPointerException     if path or namespace is null
    @JsonCreator
    public ProfileImport(
            @JsonProperty("path") @NonNull String path, @JsonProperty("namespace") @NonNull String namespace) {
        Objects.requireNonNull(path, "ProfileImport path cannot be null");
        Objects.requireNonNull(namespace, "ProfileImport namespace cannot be null");
        if (path.isBlank()) {
            throw new IllegalArgumentException("ProfileImport path cannot be blank");
        }
        if (namespace.isBlank()) {
            throw new IllegalArgumentException("ProfileImport namespace cannot be blank");
        }
        if (namespace.contains(".")) {
            throw new IllegalArgumentException("ProfileImport namespace cannot contain '.': " + namespace);
        }
        this.path = path;
        this.namespace = namespace;
    }
}
