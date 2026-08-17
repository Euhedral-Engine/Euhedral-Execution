package calibration.comparisons.schema;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Compatibility analysis result for comparing baseline and candidate runs.
public record ComparisonCompatibility(
        @NonNull CompatibilityStatus status,
        @NonNull List<ConfigurationDifference> differences,
        @NonNull List<String> reasons) {

    public ComparisonCompatibility {
        Objects.requireNonNull(status, "status must not be null");
        differences = differences == null ? List.of() : List.copyOf(differences);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public static ComparisonCompatibility compatible() {
        return new ComparisonCompatibility(CompatibilityStatus.COMPATIBLE, List.of(), List.of());
    }

    public static ComparisonCompatibility partial(
            @NonNull List<ConfigurationDifference> differences, @NonNull List<String> reasons) {
        return new ComparisonCompatibility(CompatibilityStatus.PARTIAL, differences, reasons);
    }

    public static ComparisonCompatibility incompatible(
            @NonNull List<ConfigurationDifference> differences, @NonNull List<String> reasons) {
        return new ComparisonCompatibility(CompatibilityStatus.INCOMPATIBLE, differences, reasons);
    }

    public boolean isComparable() {
        return status != CompatibilityStatus.INCOMPATIBLE;
    }
}
