package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Provenance origin metadata detailing how a trial configuration was produced.
public record TrialOrigin(
        @NonNull OriginType type,
        @Nullable String sourceId,
        @Nullable Long seed,
        @Nullable Integer candidateIndex,
        @Nullable Integer sampleIndex) {

    /// Convenience constructor for TrialOrigin without sampleIndex.
    public TrialOrigin(
            @NonNull OriginType type,
            @Nullable String sourceId,
            @Nullable Long seed,
            @Nullable Integer candidateIndex) {
        this(type, sourceId, seed, candidateIndex, null);
    }

    /// Creates and validates a TrialOrigin instance.
    ///
    /// @throws IllegalArgumentException if MANUAL origin specifies a sourceId, sourceId is blank when present,
    ///                                  candidateIndex < 0, or sampleIndex < 0
    /// @throws NullPointerException     if type is null
    @JsonCreator
    public TrialOrigin(
            @JsonProperty("type") @NonNull OriginType type,
            @JsonProperty("sourceId") @Nullable String sourceId,
            @JsonProperty("seed") @Nullable Long seed,
            @JsonProperty("candidateIndex") @Nullable Integer candidateIndex,
            @JsonProperty("sampleIndex") @Nullable Integer sampleIndex) {
        Objects.requireNonNull(type, "TrialOrigin type cannot be null");
        if (type == OriginType.MANUAL && sourceId != null) {
            throw new IllegalArgumentException("MANUAL TrialOrigin type requires no sourceId");
        }
        if (sourceId != null && sourceId.isBlank()) {
            throw new IllegalArgumentException("TrialOrigin sourceId cannot be blank if present");
        }
        if (candidateIndex != null && candidateIndex < 0) {
            throw new IllegalArgumentException("TrialOrigin candidateIndex must be >= 0 if present: " + candidateIndex);
        }
        if (sampleIndex != null && sampleIndex < 0) {
            throw new IllegalArgumentException("TrialOrigin sampleIndex must be >= 0 if present: " + sampleIndex);
        }
        this.type = type;
        this.sourceId = sourceId;
        this.seed = seed;
        this.candidateIndex = candidateIndex;
        this.sampleIndex = sampleIndex;
    }
}
