package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/// Configuration for describing comparison relationships between trials within a harness.
public record TrialComparisonConfig(
        @Nullable String baselineTrialId,
        @Nullable String comparisonGroup,
        @Nullable String purpose) {

    /// Creates and validates a TrialComparisonConfig instance.
    ///
    /// @throws IllegalArgumentException if any non-null field is blank
    @JsonCreator
    public TrialComparisonConfig(
            @JsonProperty("baselineTrialId") @Nullable String baselineTrialId,
            @JsonProperty("comparisonGroup") @Nullable String comparisonGroup,
            @JsonProperty("purpose") @Nullable String purpose) {
        if (baselineTrialId != null && baselineTrialId.isBlank()) {
            throw new IllegalArgumentException("TrialComparisonConfig baselineTrialId cannot be blank if present");
        }
        if (comparisonGroup != null && comparisonGroup.isBlank()) {
            throw new IllegalArgumentException("TrialComparisonConfig comparisonGroup cannot be blank if present");
        }
        if (purpose != null && purpose.isBlank()) {
            throw new IllegalArgumentException("TrialComparisonConfig purpose cannot be blank if present");
        }
        this.baselineTrialId = baselineTrialId;
        this.comparisonGroup = comparisonGroup;
        this.purpose = purpose;
    }
}
