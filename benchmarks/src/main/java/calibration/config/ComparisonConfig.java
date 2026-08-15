package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/// Configuration for describing comparison relationships between trials.
public record ComparisonConfig(
        @Nullable String baselineTrialId,
        @Nullable String comparisonGroup,
        @Nullable String purpose) {

    /// Creates and validates a ComparisonConfig instance.
    ///
    /// @throws IllegalArgumentException if any non-null field is blank
    @JsonCreator
    public ComparisonConfig(
            @JsonProperty("baselineTrialId") @Nullable String baselineTrialId,
            @JsonProperty("comparisonGroup") @Nullable String comparisonGroup,
            @JsonProperty("purpose") @Nullable String purpose) {
        if (baselineTrialId != null && baselineTrialId.isBlank()) {
            throw new IllegalArgumentException("ComparisonConfig baselineTrialId cannot be blank if present");
        }
        if (comparisonGroup != null && comparisonGroup.isBlank()) {
            throw new IllegalArgumentException("ComparisonConfig comparisonGroup cannot be blank if present");
        }
        if (purpose != null && purpose.isBlank()) {
            throw new IllegalArgumentException("ComparisonConfig purpose cannot be blank if present");
        }
        this.baselineTrialId = baselineTrialId;
        this.comparisonGroup = comparisonGroup;
        this.purpose = purpose;
    }
}
