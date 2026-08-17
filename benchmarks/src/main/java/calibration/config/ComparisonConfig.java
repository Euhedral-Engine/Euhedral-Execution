package calibration.config;

import calibration.comparisons.schema.ComparisonRequest;
import calibration.comparisons.schema.RunReference;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Configuration for running post-run comparison benchmarks across multiple completed trial runs.
public record ComparisonConfig(
        @NonNull RunReference baseline,
        @NonNull List<RunReference> candidates,
        @NonNull ComparisonOptions options,
        @NonNull String outputDirectory) {

    /// Creates and validates a ComparisonConfig instance.
    ///
    /// @throws NullPointerException if baseline, candidates, or outputDirectory is null
    /// @throws IllegalArgumentException if candidates is empty, outputDirectory is blank, baseline appears in
    ///         candidates, or duplicate candidates are provided
    @JsonCreator
    public ComparisonConfig(
            @JsonProperty("baseline") @NonNull RunReference baseline,
            @JsonProperty("candidates") @NonNull List<RunReference> candidates,
            @JsonProperty("options") @Nullable ComparisonOptions options,
            @JsonProperty("outputDirectory") @NonNull String outputDirectory) {
        Objects.requireNonNull(baseline, "ComparisonConfig baseline cannot be null");
        Objects.requireNonNull(candidates, "ComparisonConfig candidates cannot be null");
        Objects.requireNonNull(outputDirectory, "ComparisonConfig outputDirectory cannot be null");

        if (outputDirectory.isBlank()) {
            throw new IllegalArgumentException("ComparisonConfig outputDirectory cannot be blank");
        }

        ComparisonOptions resolvedOptions = options != null ? options : ComparisonOptions.DEFAULT;

        // Validation against request schema invariants
        ComparisonRequest request = new ComparisonRequest(baseline, candidates, resolvedOptions);

        this.baseline = request.baseline();
        this.candidates = request.candidates();
        this.options = request.options();
        this.outputDirectory = outputDirectory.trim();
    }

    public ComparisonConfig(
            @NonNull RunReference baseline, @NonNull List<RunReference> candidates, @NonNull String outputDirectory) {
        this(baseline, candidates, ComparisonOptions.DEFAULT, outputDirectory);
    }

    /// Converts this configuration to an internal ComparisonRequest.
    public ComparisonRequest toRequest() {
        return new ComparisonRequest(baseline, candidates, options);
    }
}
