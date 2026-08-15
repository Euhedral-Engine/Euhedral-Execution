package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Configuration for automated candidate generation search runs.
public record SearchConfig(
        @NonNull String id,
        @NonNull SearchStrategy strategy,
        int maxTrials,
        @Nullable Long seed,
        @Nullable String objective,
        @Nullable List<String> sweepIds,
        @Nullable Map<String, String> metadata) {

    /// Creates and validates a SearchConfig instance.
    ///
    /// @throws IllegalArgumentException if id is blank, maxTrials <= 0, objective is blank when present,
    ///                                  metadata key/val is blank, or sweepIds contain blank elements
    /// @throws NullPointerException     if id or strategy is null
    @JsonCreator
    public SearchConfig(
            @JsonProperty("id") @NonNull String id,
            @JsonProperty("strategy") @NonNull SearchStrategy strategy,
            @JsonProperty("maxTrials") int maxTrials,
            @JsonProperty("seed") @Nullable Long seed,
            @JsonProperty("objective") @Nullable String objective,
            @JsonProperty("sweepIds") @Nullable List<String> sweepIds,
            @JsonProperty("metadata") @Nullable Map<String, String> metadata) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("SearchConfig id cannot be blank");
        }
        Objects.requireNonNull(strategy, "SearchConfig strategy cannot be null");
        if (maxTrials <= 0) {
            throw new IllegalArgumentException("SearchConfig maxTrials must be positive: " + maxTrials);
        }
        if (objective != null && objective.isBlank()) {
            throw new IllegalArgumentException("SearchConfig objective cannot be blank if present");
        }
        if (sweepIds != null) {
            for (String sweepId : sweepIds) {
                if (sweepId == null || sweepId.isBlank()) {
                    throw new IllegalArgumentException("SearchConfig sweepId element cannot be blank");
                }
            }
            sweepIds = List.copyOf(sweepIds);
        }
        if (metadata != null) {
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue();
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("SearchConfig metadata key cannot be blank");
                }
                if (val == null || val.isBlank()) {
                    throw new IllegalArgumentException("SearchConfig metadata value cannot be blank for key: " + key);
                }
            }
            metadata = Map.copyOf(metadata);
        }
        this.id = id;
        this.strategy = strategy;
        this.maxTrials = maxTrials;
        this.seed = seed;
        this.objective = objective;
        this.sweepIds = sweepIds;
        this.metadata = metadata;
    }
}
