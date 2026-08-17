package calibration.comparisons.schema;

import calibration.config.ComparisonOptions;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Request for comparing a baseline completed run against one or more candidate runs.
public record ComparisonRequest(
        @NonNull RunReference baseline,
        @NonNull List<RunReference> candidates,
        @NonNull ComparisonOptions options) {

    @JsonCreator
    public ComparisonRequest(
            @JsonProperty("baseline") @NonNull RunReference baseline,
            @JsonProperty("candidates") @NonNull List<RunReference> candidates,
            @JsonProperty("options") @Nullable ComparisonOptions options) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must contain at least one candidate run reference");
        }
        this.baseline = baseline;
        this.options = options != null ? options : ComparisonOptions.DEFAULT;

        candidates = List.copyOf(candidates);

        for (RunReference candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate element must not be null");
            if (candidate.equals(baseline) || candidate.path().equals(baseline.path())) {
                throw new IllegalArgumentException("baseline cannot also appear as a candidate: " + candidate.path());
            }
        }

        Set<String> seenPaths = new HashSet<>();
        for (RunReference candidate : candidates) {
            if (!seenPaths.add(candidate.path())) {
                throw new IllegalArgumentException("duplicate candidate run reference path: " + candidate.path());
            }
        }
        this.candidates = candidates;
    }

    public ComparisonRequest(@NonNull RunReference baseline, @NonNull List<RunReference> candidates) {
        this(baseline, candidates, ComparisonOptions.DEFAULT);
    }
}
