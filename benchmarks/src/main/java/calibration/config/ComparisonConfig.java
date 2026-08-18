package calibration.config;

import calibration.comparisons.schema.ComparisonRequest;
import calibration.comparisons.schema.RunReference;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Configuration for running post-run comparison benchmarks across multiple completed trial runs or an entire
/// experiment directory.
public record ComparisonConfig(
        @Nullable String experimentDirectory,
        @Nullable RunReference baseline,
        @Nullable List<RunReference> candidates,
        @NonNull ComparisonOptions options,
        @Nullable String outputDirectory) {

    /// Creates and validates a ComparisonConfig instance.
    @JsonCreator
    public ComparisonConfig(
            @JsonProperty("experimentDirectory") @Nullable String experimentDirectory,
            @JsonProperty("baseline") @Nullable RunReference baseline,
            @JsonProperty("candidates") @Nullable List<RunReference> candidates,
            @JsonProperty("options") @Nullable ComparisonOptions options,
            @JsonProperty("outputDirectory") @Nullable String outputDirectory) {
        if (experimentDirectory != null && experimentDirectory.isBlank()) {
            throw new IllegalArgumentException("ComparisonConfig experimentDirectory cannot be blank");
        }
        if (outputDirectory != null && outputDirectory.isBlank()) {
            throw new IllegalArgumentException("ComparisonConfig outputDirectory cannot be blank");
        }

        ComparisonOptions resolvedOptions = options != null ? options : ComparisonOptions.DEFAULT;

        if (experimentDirectory == null) {
            Objects.requireNonNull(
                    baseline, "ComparisonConfig baseline cannot be null when experimentDirectory is not provided");
            Objects.requireNonNull(
                    candidates, "ComparisonConfig candidates cannot be null when experimentDirectory is not provided");
            Objects.requireNonNull(
                    outputDirectory,
                    "ComparisonConfig outputDirectory cannot be null when experimentDirectory is not provided");

            ComparisonRequest request = new ComparisonRequest(baseline, candidates, resolvedOptions);
            this.baseline = request.baseline();
            this.candidates = request.candidates();
            this.options = request.options();
            this.outputDirectory = outputDirectory.trim();
            this.experimentDirectory = null;
        } else {
            if (candidates != null && !candidates.isEmpty()) {
                if (baseline != null) {
                    ComparisonRequest request = new ComparisonRequest(baseline, candidates, resolvedOptions);
                    this.baseline = request.baseline();
                    this.candidates = request.candidates();
                } else {
                    Set<String> seen = new HashSet<>();
                    for (RunReference cand : candidates) {
                        Objects.requireNonNull(cand, "candidate cannot be null");
                        if (!seen.add(cand.path())) {
                            throw new IllegalArgumentException("Duplicate candidate path: " + cand.path());
                        }
                    }
                    this.baseline = null;
                    this.candidates = List.copyOf(candidates);
                }
            } else {
                this.baseline = baseline;
                this.candidates = candidates != null ? List.copyOf(candidates) : null;
            }
            this.options = resolvedOptions;
            this.outputDirectory = outputDirectory != null ? outputDirectory.trim() : null;
            this.experimentDirectory = experimentDirectory.trim();
        }
    }

    public ComparisonConfig(
            @NonNull RunReference baseline,
            @NonNull List<RunReference> candidates,
            @Nullable ComparisonOptions options,
            @NonNull String outputDirectory) {
        this(null, baseline, candidates, options, outputDirectory);
    }

    public ComparisonConfig(
            @NonNull RunReference baseline, @NonNull List<RunReference> candidates, @NonNull String outputDirectory) {
        this(baseline, candidates, ComparisonOptions.DEFAULT, outputDirectory);
    }

    public static ComparisonConfig ofExperimentDirectory(@NonNull String experimentDirectory) {
        return new ComparisonConfig(experimentDirectory, null, null, ComparisonOptions.DEFAULT, null);
    }

    public static ComparisonConfig ofExperimentDirectory(
            @NonNull String experimentDirectory, @Nullable String baseline, @Nullable String outputDirectory) {
        return new ComparisonConfig(
                experimentDirectory,
                baseline != null ? RunReference.of(baseline) : null,
                null,
                ComparisonOptions.DEFAULT,
                outputDirectory);
    }

    /// Converts this configuration to an internal ComparisonRequest.
    public ComparisonRequest toRequest() {
        if (baseline == null || candidates == null) {
            throw new IllegalStateException(
                    "Cannot convert to ComparisonRequest without explicit baseline and candidates");
        }
        return new ComparisonRequest(baseline, candidates, options);
    }
}
