package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Parameter entry within a sweep specification.
public record SweepParameter(
        @NonNull String path,
        @Nullable String description,
        @NonNull List<JsonNode> values) {

    /// Convenience constructor for SweepParameter without description.
    public SweepParameter(@NonNull String path, @NonNull List<JsonNode> values) {
        this(path, null, values);
    }

    /// Creates and validates a SweepParameter instance.
    ///
    /// @throws IllegalArgumentException if path or description is blank, values is empty, or values contain null
    /// @throws NullPointerException     if path or values is null
    @JsonCreator
    public SweepParameter(
            @JsonProperty("path") @NonNull String path,
            @JsonProperty("description") @Nullable String description,
            @JsonProperty("values") @NonNull List<JsonNode> values) {
        if (path.isBlank()) {
            throw new IllegalArgumentException("SweepParameter path cannot be blank");
        }
        if (description != null && description.isBlank()) {
            throw new IllegalArgumentException("SweepParameter description cannot be blank if present");
        }
        Objects.requireNonNull(values, "SweepParameter values cannot be null");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("SweepParameter values cannot be empty");
        }
        for (JsonNode val : values) {
            if (val == null || val.isNull()) {
                throw new IllegalArgumentException("SweepParameter values cannot contain null");
            }
        }
        this.path = path;
        this.description = description;
        this.values = List.copyOf(values);
    }
}
