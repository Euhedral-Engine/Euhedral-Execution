package calibration.comparisons.schema;

import org.jspecify.annotations.Nullable;

/// Options controlling post-run calibration comparison behavior.
public record ComparisonOptions(
        boolean includeDiagnostics,
        boolean failFast,
        @Nullable String outputDirectory) {

    public static final ComparisonOptions DEFAULT = new ComparisonOptions(true, false, null);

    public static ComparisonOptions defaults() {
        return DEFAULT;
    }
}
