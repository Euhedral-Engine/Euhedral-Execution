package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/// Options controlling post-run calibration comparison behavior.
public record ComparisonOptions(
        boolean includeDiagnostics,
        boolean failFast,
        @Nullable String outputDirectory) {

    public static final ComparisonOptions DEFAULT = new ComparisonOptions(true, false, null);

    @JsonCreator
    public ComparisonOptions(
            @JsonProperty("includeDiagnostics") @Nullable Boolean includeDiagnostics,
            @JsonProperty("failFast") @Nullable Boolean failFast,
            @JsonProperty("outputDirectory") @Nullable String outputDirectory) {
        this(
                includeDiagnostics != null ? includeDiagnostics : true,
                failFast != null ? failFast : false,
                outputDirectory);
    }

    public static ComparisonOptions defaults() {
        return DEFAULT;
    }
}
