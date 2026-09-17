package calibration.config;

import calibration.comparisons.schema.ComparisonSet;
import calibration.comparisons.schema.RunReference;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Configuration for running post-run comparison benchmarks across multiple completed trial runs or an entire
/// experiment directory.
public record ComparisonConfig(
        @NonNull ComparisonStrategy strategy,
        @Nullable String experimentDirectory,
        @Nullable ComparisonSet baseline,
        @Nullable ComparisonSet candidates,
        @Nullable ComparisonKeyConfig key,
        @Nullable String outputDirectory) {

    public ComparisonConfig {
        Objects.requireNonNull(strategy, "strategy must not be null");
        if (experimentDirectory != null && experimentDirectory.isBlank()) {
            throw new IllegalArgumentException("ComparisonConfig experimentDirectory cannot be blank");
        }
        if (outputDirectory != null && outputDirectory.isBlank()) {
            throw new IllegalArgumentException("ComparisonConfig outputDirectory cannot be blank");
        }

        if (experimentDirectory == null) {
            Objects.requireNonNull(
                    baseline, "ComparisonConfig baseline cannot be null when experimentDirectory is not provided");
            Objects.requireNonNull(
                    candidates, "ComparisonConfig candidates cannot be null when experimentDirectory is not provided");
            Objects.requireNonNull(
                    outputDirectory,
                    "ComparisonConfig outputDirectory cannot be null when experimentDirectory is not provided");

            if (baseline.runs().isEmpty()) {
                throw new IllegalArgumentException("baseline set must contain at least one run");
            }
            if (candidates.runs().isEmpty()) {
                throw new IllegalArgumentException("candidate set must contain at least one run");
            }

            if (strategy == ComparisonStrategy.BASELINE && baseline.runs().size() != 1) {
                throw new IllegalArgumentException(
                        "BASELINE comparison strategy requires exactly one baseline run, but got "
                                + baseline.runs().size());
            }

            if (strategy == ComparisonStrategy.KEYED) {
                Objects.requireNonNull(key, "KEYED strategy requires a key configuration");
                if (key.paths().isEmpty()) {
                    throw new IllegalArgumentException("KEYED strategy requires non-empty key paths");
                }
            }

            Set<String> seenCandidatePaths = new HashSet<>();
            for (RunReference cand : candidates.runs()) {
                Objects.requireNonNull(cand, "candidate run element must not be null");
                if (!seenCandidatePaths.add(cand.path())) {
                    throw new IllegalArgumentException("Duplicate candidate path: " + cand.path());
                }
            }
            outputDirectory = outputDirectory.trim();
        } else {
            outputDirectory = outputDirectory != null ? outputDirectory.trim() : null;
            experimentDirectory = experimentDirectory.trim();
        }
    }

    /// Creates and validates a ComparisonConfig instance from JSON properties.
    @JsonCreator
    public static ComparisonConfig fromJson(
            @JsonProperty("strategy") @Nullable ComparisonStrategy strategy,
            @JsonProperty("experimentDirectory") @Nullable String experimentDirectory,
            @JsonProperty("baseline") @Nullable ComparisonSet baseline,
            @JsonProperty("candidates") @Nullable ComparisonSet candidates,
            @JsonProperty("key") @Nullable ComparisonKeyConfig key,
            @JsonProperty("outputDirectory") @Nullable String outputDirectory) {
        ComparisonStrategy resolvedStrategy = strategy != null ? strategy : ComparisonStrategy.BASELINE;
        return new ComparisonConfig(resolvedStrategy, experimentDirectory, baseline, candidates, key, outputDirectory);
    }

    public static ComparisonConfig ofExperimentDirectory(@NonNull String experimentDirectory) {
        return new ComparisonConfig(ComparisonStrategy.BASELINE, experimentDirectory, null, null, null, null);
    }

    public static ComparisonConfig ofExperimentDirectory(
            @NonNull String experimentDirectory, @Nullable String baseline, @Nullable String outputDirectory) {
        return new ComparisonConfig(
                ComparisonStrategy.BASELINE,
                experimentDirectory,
                baseline != null ? ComparisonSet.ofSingle(RunReference.of(baseline)) : null,
                null,
                null,
                outputDirectory);
    }
}
