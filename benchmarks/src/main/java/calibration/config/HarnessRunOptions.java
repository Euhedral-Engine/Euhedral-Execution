package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/// Run-control options for harness trial execution.
/// Null fields indicate that harness default behavior should be used when the feature is wired in later.
public record HarnessRunOptions(
        @Nullable Boolean randomizeTrialOrder,
        @Nullable Long randomSeed,
        @Nullable Boolean failFast,
        @Nullable Integer repeatCount) {

    /// Creates and validates a HarnessRunOptions instance.
    ///
    /// @throws IllegalArgumentException if repeatCount is present and less than 1
    @JsonCreator
    public HarnessRunOptions(
            @JsonProperty("randomizeTrialOrder") @Nullable Boolean randomizeTrialOrder,
            @JsonProperty("randomSeed") @Nullable Long randomSeed,
            @JsonProperty("failFast") @Nullable Boolean failFast,
            @JsonProperty("repeatCount") @Nullable Integer repeatCount) {
        if (repeatCount != null && repeatCount < 1) {
            throw new IllegalArgumentException(
                    "HarnessRunOptions repeatCount must be positive if present: " + repeatCount);
        }
        this.randomizeTrialOrder = randomizeTrialOrder;
        this.randomSeed = randomSeed;
        this.failFast = failFast;
        this.repeatCount = repeatCount;
    }
}
