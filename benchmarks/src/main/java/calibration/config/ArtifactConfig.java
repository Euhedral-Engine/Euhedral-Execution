package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/// Result-retention configuration for benchmark execution outputs and artifacts.
public record ArtifactConfig(
        @Nullable String outputDirectory,
        @Nullable Boolean retainExpandedConfig,
        @Nullable Boolean retainRawBenchmarkOutput,
        @Nullable Boolean retainObserverData,
        @Nullable Boolean retainPerForkResults,
        @Nullable Boolean retainPerIterationResults) {

    /// Creates and validates an ArtifactConfig instance.
    ///
    /// @throws IllegalArgumentException if outputDirectory is present and blank
    @JsonCreator
    public ArtifactConfig(
            @JsonProperty("outputDirectory") @Nullable String outputDirectory,
            @JsonProperty("retainExpandedConfig") @Nullable Boolean retainExpandedConfig,
            @JsonProperty("retainRawBenchmarkOutput") @Nullable Boolean retainRawBenchmarkOutput,
            @JsonProperty("retainObserverData") @Nullable Boolean retainObserverData,
            @JsonProperty("retainPerForkResults") @Nullable Boolean retainPerForkResults,
            @JsonProperty("retainPerIterationResults") @Nullable Boolean retainPerIterationResults) {
        if (outputDirectory != null && outputDirectory.isBlank()) {
            throw new IllegalArgumentException("ArtifactConfig outputDirectory cannot be blank if present");
        }
        this.outputDirectory = outputDirectory;
        this.retainExpandedConfig = retainExpandedConfig;
        this.retainRawBenchmarkOutput = retainRawBenchmarkOutput;
        this.retainObserverData = retainObserverData;
        this.retainPerForkResults = retainPerForkResults;
        this.retainPerIterationResults = retainPerIterationResults;
    }
}
