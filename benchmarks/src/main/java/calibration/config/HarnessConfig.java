package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Configuration for benchmark calibration harness execution.
/// Holds optional metadata, run options, artifact retention settings, reusable calibration and decision weight
/// profiles, parameter sweeps, and non-empty trial specifications.
public record HarnessConfig(
        @Nullable Integer schemaVersion,
        @Nullable String id,
        @Nullable String name,
        @Nullable String description,
        @Nullable Map<String, String> labels,
        @Nullable HarnessRunOptions runOptions,
        @Nullable ArtifactConfig artifacts,
        @Nullable Map<String, CalibrationBenchmarkConfig> calibrationProfiles,
        @Nullable Map<String, FragmentDecisionWeights> decisionWeightProfiles,
        @Nullable List<SweepConfig> sweeps,
        @NonNull List<TrialConfig> trials) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /// Convenience constructor for harness configs containing only trials.
    public HarnessConfig(@NonNull List<TrialConfig> trials) {
        this(null, null, null, null, null, null, null, null, null, null, trials);
    }

    /// Convenience constructor for harness configs without runOptions, artifacts, profiles, and sweeps.
    public HarnessConfig(
            @Nullable Integer schemaVersion,
            @Nullable String id,
            @Nullable String name,
            @Nullable String description,
            @Nullable Map<String, String> labels,
            @NonNull List<TrialConfig> trials) {
        this(schemaVersion, id, name, description, labels, null, null, null, null, null, trials);
    }

    /// Convenience constructor for harness configs without profiles and sweeps.
    public HarnessConfig(
            @Nullable Integer schemaVersion,
            @Nullable String id,
            @Nullable String name,
            @Nullable String description,
            @Nullable Map<String, String> labels,
            @Nullable HarnessRunOptions runOptions,
            @Nullable ArtifactConfig artifacts,
            @NonNull List<TrialConfig> trials) {
        this(schemaVersion, id, name, description, labels, runOptions, artifacts, null, null, null, trials);
    }

    /// Convenience constructor for harness configs without decisionWeightProfiles and sweeps.
    public HarnessConfig(
            @Nullable Integer schemaVersion,
            @Nullable String id,
            @Nullable String name,
            @Nullable String description,
            @Nullable Map<String, String> labels,
            @Nullable HarnessRunOptions runOptions,
            @Nullable ArtifactConfig artifacts,
            @Nullable Map<String, CalibrationBenchmarkConfig> calibrationProfiles,
            @NonNull List<TrialConfig> trials) {
        this(
                schemaVersion,
                id,
                name,
                description,
                labels,
                runOptions,
                artifacts,
                calibrationProfiles,
                null,
                null,
                trials);
    }

    /// Convenience constructor for harness configs without sweeps.
    public HarnessConfig(
            @Nullable Integer schemaVersion,
            @Nullable String id,
            @Nullable String name,
            @Nullable String description,
            @Nullable Map<String, String> labels,
            @Nullable HarnessRunOptions runOptions,
            @Nullable ArtifactConfig artifacts,
            @Nullable Map<String, CalibrationBenchmarkConfig> calibrationProfiles,
            @Nullable Map<String, FragmentDecisionWeights> decisionWeightProfiles,
            @NonNull List<TrialConfig> trials) {
        this(
                schemaVersion,
                id,
                name,
                description,
                labels,
                runOptions,
                artifacts,
                calibrationProfiles,
                decisionWeightProfiles,
                null,
                trials);
    }

    /// Creates and validates a HarnessConfig instance.
    ///
    /// @throws IllegalArgumentException if schemaVersion is non-positive, id or name is blank,
    ///                                  trials is empty, non-null trial IDs are duplicated,
    ///                                  referenced baselineTrialIds are invalid/self-referential,
    ///                                  calibrationProfiles/decisionWeightProfiles keys are blank,
    ///                                  or sweep IDs are duplicated
    /// @throws NullPointerException     if trials is null or profiles/sweeps maps/lists contain null values
    @JsonCreator
    public HarnessConfig {
        if (schemaVersion != null && schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be positive if present: " + schemaVersion);
        }
        if (id != null && id.isBlank()) {
            throw new IllegalArgumentException("id cannot be blank if present");
        }
        if (name != null && name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank if present");
        }
        labels = labels != null ? Map.copyOf(labels) : null;
        if (calibrationProfiles != null) {
            for (Map.Entry<String, CalibrationBenchmarkConfig> entry : calibrationProfiles.entrySet()) {
                String profileName = entry.getKey();
                if (profileName == null || profileName.isBlank()) {
                    throw new IllegalArgumentException("calibrationProfiles profile name cannot be blank");
                }
                Objects.requireNonNull(
                        entry.getValue(), "calibrationProfiles profile value cannot be null for: " + profileName);
            }
            calibrationProfiles = Map.copyOf(calibrationProfiles);
        }
        if (decisionWeightProfiles != null) {
            for (Map.Entry<String, FragmentDecisionWeights> entry : decisionWeightProfiles.entrySet()) {
                String profileName = entry.getKey();
                if (profileName == null || profileName.isBlank()) {
                    throw new IllegalArgumentException("decisionWeightProfiles profile name cannot be blank");
                }
                Objects.requireNonNull(
                        entry.getValue(), "decisionWeightProfiles profile value cannot be null for: " + profileName);
            }
            decisionWeightProfiles = Map.copyOf(decisionWeightProfiles);
        }
        if (sweeps != null) {
            Set<String> sweepIds = new HashSet<>();
            for (SweepConfig sweep : sweeps) {
                Objects.requireNonNull(sweep, "sweep element cannot be null");
                if (!sweepIds.add(sweep.id())) {
                    throw new IllegalArgumentException("Duplicate sweep id found: " + sweep.id());
                }
            }
            sweeps = List.copyOf(sweeps);
        }
        Objects.requireNonNull(trials, "trials cannot be null");
        if (trials.isEmpty()) {
            throw new IllegalArgumentException("Trial configurations can not be empty");
        }

        Set<String> trialIds = new HashSet<>();
        for (TrialConfig trial : trials) {
            if (trial != null && trial.id() != null) {
                if (!trialIds.add(trial.id())) {
                    throw new IllegalArgumentException("Duplicate trial id found: " + trial.id());
                }
            }
        }

        for (TrialConfig trial : trials) {
            if (trial != null
                    && trial.comparison() != null
                    && trial.comparison().baselineTrialId() != null) {
                String baselineId = trial.comparison().baselineTrialId();
                if (trial.id() != null && trial.id().equals(baselineId)) {
                    throw new IllegalArgumentException("Trial cannot reference itself as baseline: " + trial.id());
                }
                if (!trialIds.contains(baselineId)) {
                    throw new IllegalArgumentException("Referenced baselineTrialId not found: " + baselineId);
                }
            }
        }
    }

    /// Parameter sweep specification for parameter variation.
    public record SweepConfig(
            @NonNull String id,
            @Nullable String description,
            @NonNull List<SweepParameter> parameters) {

        /// Creates and validates a SweepConfig instance.
        ///
        /// @throws IllegalArgumentException if id is blank, description is blank when present,
        ///                                  parameters is empty, or parameter paths are duplicated
        /// @throws NullPointerException     if id or parameters is null or contains null elements
        @JsonCreator
        public SweepConfig {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Sweep id cannot be blank");
            }
            if (description != null && description.isBlank()) {
                throw new IllegalArgumentException("Sweep description cannot be blank if present");
            }
            Objects.requireNonNull(parameters, "Sweep parameters cannot be null");
            if (parameters.isEmpty()) {
                throw new IllegalArgumentException("Sweep parameters cannot be empty");
            }
            Set<String> parameterPaths = new HashSet<>();
            for (SweepParameter param : parameters) {
                Objects.requireNonNull(param, "Sweep parameter cannot be null");
                if (!parameterPaths.add(param.path())) {
                    throw new IllegalArgumentException(
                            "Duplicate parameter path in sweep '" + id + "': " + param.path());
                }
            }
            parameters = List.copyOf(parameters);
        }
    }

    /// Parameter entry within a sweep specification.
    public record SweepParameter(
            @NonNull String path, @NonNull List<JsonNode> values) {

        /// Creates and validates a SweepParameter instance.
        ///
        /// @throws IllegalArgumentException if path is blank, values is empty, or values contain null
        /// @throws NullPointerException     if path or values is null
        @JsonCreator
        public SweepParameter {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("SweepParameter path cannot be blank");
            }
            Objects.requireNonNull(values, "SweepParameter values cannot be null");
            if (values.isEmpty()) {
                throw new IllegalArgumentException("SweepParameter values cannot be empty");
            }
            for (JsonNode val : values) {
                if (val == null || val.isNull()) {
                    throw new IllegalArgumentException("SweepParameter values cannot contain null");
                }
            }
            values = List.copyOf(values);
        }
    }

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
        public HarnessRunOptions {
            if (repeatCount != null && repeatCount < 1) {
                throw new IllegalArgumentException("repeatCount must be positive if present: " + repeatCount);
            }
        }
    }

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
        public ArtifactConfig {
            if (outputDirectory != null && outputDirectory.isBlank()) {
                throw new IllegalArgumentException("outputDirectory cannot be blank if present");
            }
        }
    }

    /// Configuration for describing comparison relationships between trials.
    public record ComparisonConfig(
            @Nullable String baselineTrialId,
            @Nullable String comparisonGroup,
            @Nullable String purpose) {

        /// Creates and validates a ComparisonConfig instance.
        ///
        /// @throws IllegalArgumentException if any non-null field is blank
        @JsonCreator
        public ComparisonConfig {
            if (baselineTrialId != null && baselineTrialId.isBlank()) {
                throw new IllegalArgumentException("baselineTrialId cannot be blank if present");
            }
            if (comparisonGroup != null && comparisonGroup.isBlank()) {
                throw new IllegalArgumentException("comparisonGroup cannot be blank if present");
            }
            if (purpose != null && purpose.isBlank()) {
                throw new IllegalArgumentException("purpose cannot be blank if present");
            }
        }
    }

    /// Configuration for an individual calibration trial run.
    public record TrialConfig(
            @Nullable String id,
            @Nullable String name,
            @Nullable String group,
            @Nullable String description,
            @Nullable String hypothesis,
            @Nullable ComparisonConfig comparison,
            @Nullable List<String> tags,
            @Nullable Boolean enabled,
            int forks,
            int warmups,
            int iterations,
            @Nullable List<String> jvmArgs,
            @NonNull CalibrationBenchmarkConfig calibrationConfig) {

        /// Convenience constructor for execution properties without trial metadata.
        public TrialConfig(
                int forks,
                int warmups,
                int iterations,
                @Nullable List<String> jvmArgs,
                @NonNull CalibrationBenchmarkConfig calibrationConfig) {
            this(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    forks,
                    warmups,
                    iterations,
                    jvmArgs,
                    calibrationConfig);
        }

        /// Creates and validates a TrialConfig instance.
        ///
        /// @throws IllegalArgumentException if any non-null string metadata is blank or tags contain blank values
        /// @throws NullPointerException     if calibrationConfig is null or tags contain null elements
        @JsonCreator
        public TrialConfig {
            if (id != null && id.isBlank()) {
                throw new IllegalArgumentException("trial id cannot be blank if present");
            }
            if (name != null && name.isBlank()) {
                throw new IllegalArgumentException("trial name cannot be blank if present");
            }
            if (group != null && group.isBlank()) {
                throw new IllegalArgumentException("trial group cannot be blank if present");
            }
            if (tags != null) {
                for (String tag : tags) {
                    Objects.requireNonNull(tag, "tag cannot be null");
                    if (tag.isBlank()) {
                        throw new IllegalArgumentException("tag cannot be blank");
                    }
                }
                tags = List.copyOf(tags);
            }
            Objects.requireNonNull(calibrationConfig, "calibrationConfig cannot be null");
        }
    }
}
