package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/// Options controlling post-run calibration comparison behavior.
public record ComparisonOptions(
        boolean includeDiagnostics,
        boolean failFast,
        ComparisonScope scope,
        @Nullable String outputDirectory) {

    public ComparisonOptions {
        Objects.requireNonNull(scope, "scope must not be null");
    }

    public static final ComparisonOptions DEFAULT = new ComparisonOptions(true, false, ComparisonScope.RUN, null);

    @JsonCreator
    public ComparisonOptions(
            @JsonProperty("includeDiagnostics") @Nullable Boolean includeDiagnostics,
            @JsonProperty("failFast") @Nullable Boolean failFast,
            @JsonProperty("scope") @Nullable ComparisonScope scope,
            @JsonProperty("outputDirectory") @Nullable String outputDirectory) {
        this(
                includeDiagnostics != null ? includeDiagnostics : true,
                failFast != null ? failFast : false,
                scope != null ? scope : ComparisonScope.RUN,
                outputDirectory);
    }

    public static ComparisonOptions defaults() {
        return DEFAULT;
    }
}
