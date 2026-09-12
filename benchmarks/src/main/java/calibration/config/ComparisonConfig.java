package calibration.config;

import calibration.comparisons.schema.ComparisonRequest;
import calibration.comparisons.schema.ComparisonSet;
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
        @NonNull ComparisonStrategy strategy,
        @Nullable String experimentDirectory,
        @Nullable ComparisonSet baseline,
        @Nullable ComparisonSet candidate,
        @Nullable ComparisonKeyConfig key,
        @NonNull ComparisonOptions options,
        @Nullable String outputDirectory) {

    public ComparisonConfig {
        Objects.requireNonNull(strategy, "strategy must not be null");
        Objects.requireNonNull(options, "options must not be null");

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
                    candidate,
                    "ComparisonConfig candidate/candidates cannot be null when experimentDirectory is not provided");
            Objects.requireNonNull(
                    outputDirectory,
                    "ComparisonConfig outputDirectory cannot be null when experimentDirectory is not provided");

            if (baseline.runs().isEmpty()) {
                throw new IllegalArgumentException("baseline set must contain at least one run");
            }
            if (candidate.runs().isEmpty()) {
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
            for (RunReference cand : candidate.runs()) {
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
            @JsonProperty("candidate") @Nullable ComparisonSet candidate,
            @JsonProperty("candidates") @Nullable ComparisonSet candidates,
            @JsonProperty("key") @Nullable ComparisonKeyConfig key,
            @JsonProperty("options") @Nullable ComparisonOptions options,
            @JsonProperty("outputDirectory") @Nullable String outputDirectory) {
        ComparisonStrategy resolvedStrategy = strategy != null ? strategy : ComparisonStrategy.BASELINE;
        ComparisonSet resolvedCandidate = candidate != null ? candidate : candidates;
        ComparisonOptions resolvedOptions = options != null ? options : ComparisonOptions.DEFAULT;

        return new ComparisonConfig(
                resolvedStrategy,
                experimentDirectory,
                baseline,
                resolvedCandidate,
                key,
                resolvedOptions,
                outputDirectory);
    }

    public ComparisonConfig(
            @Nullable String experimentDirectory,
            @Nullable RunReference baseline,
            @Nullable List<RunReference> candidates,
            @NonNull ComparisonOptions options,
            @Nullable String outputDirectory) {
        this(
                ComparisonStrategy.BASELINE,
                experimentDirectory,
                baseline != null ? ComparisonSet.ofSingle(baseline) : null,
                candidates != null ? ComparisonSet.ofRuns(candidates) : null,
                null,
                options,
                outputDirectory);
    }

    public ComparisonConfig(
            @NonNull RunReference baseline,
            @NonNull List<RunReference> candidates,
            @Nullable ComparisonOptions options,
            @NonNull String outputDirectory) {
        this(
                ComparisonStrategy.BASELINE,
                null,
                baseline != null ? ComparisonSet.ofSingle(baseline) : null,
                candidates != null ? ComparisonSet.ofRuns(candidates) : null,
                null,
                options != null ? options : ComparisonOptions.DEFAULT,
                outputDirectory);
    }

    public ComparisonConfig(
            @NonNull RunReference baseline, @NonNull List<RunReference> candidates, @NonNull String outputDirectory) {
        this(baseline, candidates, ComparisonOptions.DEFAULT, outputDirectory);
    }

    public ComparisonConfig(
            @NonNull ComparisonStrategy strategy,
            @NonNull ComparisonSet baseline,
            @NonNull ComparisonSet candidate,
            @Nullable ComparisonKeyConfig key,
            @Nullable ComparisonOptions options,
            @NonNull String outputDirectory) {
        this(
                strategy,
                null,
                baseline,
                candidate,
                key,
                options != null ? options : ComparisonOptions.DEFAULT,
                outputDirectory);
    }

    public ComparisonConfig(
            @NonNull ComparisonStrategy strategy,
            @NonNull ComparisonSet baseline,
            @NonNull ComparisonSet candidate,
            @Nullable ComparisonKeyConfig key,
            @NonNull String outputDirectory) {
        this(strategy, baseline, candidate, key, ComparisonOptions.DEFAULT, outputDirectory);
    }

    public static ComparisonConfig ofExperimentDirectory(@NonNull String experimentDirectory) {
        return new ComparisonConfig(
                ComparisonStrategy.BASELINE, experimentDirectory, null, null, null, ComparisonOptions.DEFAULT, null);
    }

    public static ComparisonConfig ofExperimentDirectory(
            @NonNull String experimentDirectory, @Nullable String baseline, @Nullable String outputDirectory) {
        return new ComparisonConfig(
                ComparisonStrategy.BASELINE,
                experimentDirectory,
                baseline != null ? ComparisonSet.ofSingle(RunReference.of(baseline)) : null,
                null,
                null,
                ComparisonOptions.DEFAULT,
                outputDirectory);
    }

    /// Returns candidate runs list for backwards compatibility.
    public @Nullable List<RunReference> candidates() {
        return candidate != null ? candidate.runs() : null;
    }

    /// Converts this configuration to an internal ComparisonRequest (for BASELINE mode).
    public ComparisonRequest toRequest() {
        if (baseline == null
                || baseline.runs().isEmpty()
                || candidate == null
                || candidate.runs().isEmpty()) {
            throw new IllegalStateException(
                    "Cannot convert to ComparisonRequest without explicit baseline and candidates");
        }
        return new ComparisonRequest(baseline.runs().getFirst(), candidate.runs(), options);
    }
}
