package calibration.comparisons.schema;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Structural difference between baseline and candidate configurations.
public record ConfigurationDifference(
        @NonNull String path,
        @Nullable JsonNode baselineValue,
        @Nullable JsonNode candidateValue,
        @NonNull DifferenceCategory category) {

    public ConfigurationDifference {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(category, "category must not be null");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
    }
}
