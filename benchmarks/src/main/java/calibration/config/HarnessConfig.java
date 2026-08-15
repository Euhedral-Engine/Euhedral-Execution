package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
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
/// profiles, parameter sweeps, search configurations, and non-empty trial specifications.
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
        @Nullable List<SearchConfig> searches,
        @NonNull List<TrialConfig> trials) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /// Convenience constructor for harness configs containing only trials.
    public HarnessConfig(@NonNull List<TrialConfig> trials) {
        this(null, null, null, null, null, null, null, null, null, null, null, trials);
    }

    /// Convenience constructor for harness configs without runOptions, artifacts, profiles, sweeps, and searches.
    public HarnessConfig(
            @Nullable Integer schemaVersion,
            @Nullable String id,
            @Nullable String name,
            @Nullable String description,
            @Nullable Map<String, String> labels,
            @NonNull List<TrialConfig> trials) {
        this(schemaVersion, id, name, description, labels, null, null, null, null, null, null, trials);
    }

    /// Convenience constructor for harness configs without profiles, sweeps, and searches.
    public HarnessConfig(
            @Nullable Integer schemaVersion,
            @Nullable String id,
            @Nullable String name,
            @Nullable String description,
            @Nullable Map<String, String> labels,
            @Nullable HarnessRunOptions runOptions,
            @Nullable ArtifactConfig artifacts,
            @NonNull List<TrialConfig> trials) {
        this(schemaVersion, id, name, description, labels, runOptions, artifacts, null, null, null, null, trials);
    }

    /// Convenience constructor for harness configs without decisionWeightProfiles, sweeps, and searches.
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
                null,
                trials);
    }

    /// Convenience constructor for harness configs without sweeps and searches.
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
                null,
                trials);
    }

    /// Convenience constructor for harness configs without searches.
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
            @Nullable List<SweepConfig> sweeps,
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
                sweeps,
                null,
                trials);
    }

    /// Creates and validates a HarnessConfig instance.
    ///
    /// @throws IllegalArgumentException if schemaVersion is non-positive, id/name/description is blank,
    ///                                  trials is empty, non-null trial IDs are duplicated,
    ///                                  referenced baselineTrialIds are invalid/self-referential,
    ///                                  profile keys or label keys/values are blank,
    ///                                  sweep/search IDs are duplicated, or referenced sweepIds do not exist
    /// @throws NullPointerException     if trials is null or profiles/sweeps/searches contain null values
    @JsonCreator
    public HarnessConfig {
        if (schemaVersion != null && schemaVersion <= 0) {
            throw new IllegalArgumentException(
                    "HarnessConfig schemaVersion must be positive if present: " + schemaVersion);
        }
        if (id != null && id.isBlank()) {
            throw new IllegalArgumentException("HarnessConfig id cannot be blank if present");
        }
        if (name != null && name.isBlank()) {
            throw new IllegalArgumentException("HarnessConfig name cannot be blank if present");
        }
        if (description != null && description.isBlank()) {
            throw new IllegalArgumentException("HarnessConfig description cannot be blank if present");
        }
        if (labels != null) {
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue();
                if (key == null || key.isBlank()) {
                    throw new IllegalArgumentException("HarnessConfig label key cannot be blank");
                }
                if (val == null || val.isBlank()) {
                    throw new IllegalArgumentException("HarnessConfig label value cannot be blank for key: " + key);
                }
            }
            labels = Map.copyOf(labels);
        }
        if (calibrationProfiles != null) {
            for (Map.Entry<String, CalibrationBenchmarkConfig> entry : calibrationProfiles.entrySet()) {
                String profileName = entry.getKey();
                if (profileName == null || profileName.isBlank()) {
                    throw new IllegalArgumentException("HarnessConfig calibrationProfiles key cannot be blank");
                }
                Objects.requireNonNull(
                        entry.getValue(),
                        "HarnessConfig calibrationProfiles value cannot be null for key: " + profileName);
            }
            calibrationProfiles = Map.copyOf(calibrationProfiles);
        }
        if (decisionWeightProfiles != null) {
            for (Map.Entry<String, FragmentDecisionWeights> entry : decisionWeightProfiles.entrySet()) {
                String profileName = entry.getKey();
                if (profileName == null || profileName.isBlank()) {
                    throw new IllegalArgumentException("HarnessConfig decisionWeightProfiles key cannot be blank");
                }
                Objects.requireNonNull(
                        entry.getValue(),
                        "HarnessConfig decisionWeightProfiles value cannot be null for key: " + profileName);
            }
            decisionWeightProfiles = Map.copyOf(decisionWeightProfiles);
        }
        Set<String> declaredSweepIds = new HashSet<>();
        if (sweeps != null) {
            for (SweepConfig sweep : sweeps) {
                Objects.requireNonNull(sweep, "HarnessConfig sweep element cannot be null");
                if (!declaredSweepIds.add(sweep.id())) {
                    throw new IllegalArgumentException("HarnessConfig duplicate sweep id found: " + sweep.id());
                }
            }
            sweeps = List.copyOf(sweeps);
        }
        if (searches != null) {
            Set<String> searchIds = new HashSet<>();
            for (SearchConfig search : searches) {
                Objects.requireNonNull(search, "HarnessConfig search element cannot be null");
                if (!searchIds.add(search.id())) {
                    throw new IllegalArgumentException("HarnessConfig duplicate search id found: " + search.id());
                }
                if (search.sweepIds() != null) {
                    for (String sweepId : search.sweepIds()) {
                        if (!declaredSweepIds.contains(sweepId)) {
                            throw new IllegalArgumentException("Referenced sweepId '" + sweepId + "' in SearchConfig '"
                                    + search.id() + "' was not found in declared sweeps");
                        }
                    }
                }
            }
            searches = List.copyOf(searches);
        }
        Objects.requireNonNull(trials, "HarnessConfig trials cannot be null");
        if (trials.isEmpty()) {
            throw new IllegalArgumentException("HarnessConfig trial configurations cannot be empty");
        }

        Set<String> trialIds = new HashSet<>();
        for (TrialConfig trial : trials) {
            Objects.requireNonNull(trial, "HarnessConfig trial element cannot be null");
            if (trial.id() != null) {
                if (!trialIds.add(trial.id())) {
                    throw new IllegalArgumentException("HarnessConfig duplicate trial id found: " + trial.id());
                }
            }
        }

        if (sweeps != null) {
            for (SweepConfig sweep : sweeps) {
                if (!trialIds.contains(sweep.baseTrialId())) {
                    throw new IllegalArgumentException("Referenced baseTrialId '" + sweep.baseTrialId()
                            + "' in SweepConfig '" + sweep.id() + "' was not found in trials");
                }
            }
        }

        for (TrialConfig trial : trials) {
            if (trial.comparison() != null && trial.comparison().baselineTrialId() != null) {
                String baselineId = trial.comparison().baselineTrialId();
                if (trial.id() != null && trial.id().equals(baselineId)) {
                    throw new IllegalArgumentException(
                            "TrialConfig cannot reference itself as baseline: " + trial.id());
                }
                if (!trialIds.contains(baselineId)) {
                    throw new IllegalArgumentException("Referenced baselineTrialId not found in trials: " + baselineId);
                }
            }
        }
        trials = List.copyOf(trials);
    }

    /// Strategy for automated candidate search generation.
    public enum SearchStrategy {
        GRID,
        RANDOM,
        SOBOL,
        EXTERNAL
    }

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
        public SearchConfig {
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
                        throw new IllegalArgumentException(
                                "SearchConfig metadata value cannot be blank for key: " + key);
                    }
                }
                metadata = Map.copyOf(metadata);
            }
        }
    }

    /// Parameter sweep specification for parameter variation.
    public record SweepConfig(
            @NonNull String id,
            @NonNull String baseTrialId,
            @Nullable String description,
            @Nullable Boolean enabled,
            @Nullable Integer repetitions,
            @Nullable String group,
            @Nullable Map<String, String> labels,
            @NonNull List<SweepParameter> parameters) {

        /// Convenience constructor for sweep configs without description, enabled, repetitions, group, and labels.
        public SweepConfig(@NonNull String id, @NonNull String baseTrialId, @NonNull List<SweepParameter> parameters) {
            this(id, baseTrialId, null, null, null, null, null, parameters);
        }

        /// Convenience constructor for sweep configs without enabled, repetitions, group, and labels.
        public SweepConfig(
                @NonNull String id,
                @NonNull String baseTrialId,
                @Nullable String description,
                @NonNull List<SweepParameter> parameters) {
            this(id, baseTrialId, description, null, null, null, null, parameters);
        }

        /// Convenience constructor for sweep configs without repetitions, group, and labels.
        public SweepConfig(
                @NonNull String id,
                @NonNull String baseTrialId,
                @Nullable String description,
                @Nullable Boolean enabled,
                @NonNull List<SweepParameter> parameters) {
            this(id, baseTrialId, description, enabled, null, null, null, parameters);
        }

        /// Returns true if enabled is null or true.
        @JsonIgnore
        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        /// Creates and validates a SweepConfig instance.
        ///
        /// @throws IllegalArgumentException if id or baseTrialId is blank, description, group, or label keys/values are
        /// blank when present,
        ///                                  repetitions is present and < 1, parameters is empty, or parameter paths are
        /// duplicated
        /// @throws NullPointerException     if id, baseTrialId, or parameters is null or contains null elements
        @JsonCreator
        public SweepConfig(
                @JsonProperty("id") @NonNull String id,
                @JsonProperty("baseTrialId") @NonNull String baseTrialId,
                @JsonProperty("description") @Nullable String description,
                @JsonProperty("enabled") @Nullable Boolean enabled,
                @JsonProperty("repetitions") @Nullable Integer repetitions,
                @JsonProperty("group") @Nullable String group,
                @JsonProperty("labels") @Nullable Map<String, String> labels,
                @JsonProperty("parameters") @NonNull List<SweepParameter> parameters) {
            if (id.isBlank()) {
                throw new IllegalArgumentException("SweepConfig id cannot be blank");
            }
            if (baseTrialId.isBlank()) {
                throw new IllegalArgumentException("SweepConfig baseTrialId cannot be blank");
            }
            if (description != null && description.isBlank()) {
                throw new IllegalArgumentException("SweepConfig description cannot be blank if present");
            }
            if (repetitions != null && repetitions < 1) {
                throw new IllegalArgumentException("SweepConfig repetitions must be >= 1 if present: " + repetitions);
            }
            if (group != null && group.isBlank()) {
                throw new IllegalArgumentException("SweepConfig group cannot be blank if present");
            }
            if (labels != null) {
                for (Map.Entry<String, String> entry : labels.entrySet()) {
                    String key = entry.getKey();
                    String val = entry.getValue();
                    if (key == null || key.isBlank()) {
                        throw new IllegalArgumentException("SweepConfig label key cannot be blank");
                    }
                    if (val == null || val.isBlank()) {
                        throw new IllegalArgumentException("SweepConfig label value cannot be blank for key: " + key);
                    }
                }
                labels = Map.copyOf(labels);
            }
            Objects.requireNonNull(parameters, "SweepConfig parameters cannot be null");
            if (parameters.isEmpty()) {
                throw new IllegalArgumentException("SweepConfig parameters cannot be empty");
            }
            Set<String> parameterPaths = new HashSet<>();
            for (SweepParameter param : parameters) {
                Objects.requireNonNull(param, "SweepConfig parameter element cannot be null");
                if (!parameterPaths.add(param.path())) {
                    throw new IllegalArgumentException(
                            "Duplicate parameter path in SweepConfig '" + id + "': " + param.path());
                }
            }
            this.id = id;
            this.baseTrialId = baseTrialId;
            this.description = description;
            this.enabled = enabled;
            this.repetitions = repetitions;
            this.group = group;
            this.labels = labels;
            this.parameters = List.copyOf(parameters);
        }
    }

    /// Parameter entry within a sweep specification.
    public record SweepParameter(
            @NonNull String path,
            @Nullable String description,
            @NonNull List<JsonNode> values) {

        /// Convenience constructor for SweepParameter without description.
        public SweepParameter(@NonNull String path, @NonNull List<JsonNode> values) {
            this(path, null, values);
        }

        /// Creates and validates a SweepParameter instance.
        ///
        /// @throws IllegalArgumentException if path or description is blank, values is empty, or values contain null
        /// @throws NullPointerException     if path or values is null
        @JsonCreator
        public SweepParameter(
                @JsonProperty("path") @NonNull String path,
                @JsonProperty("description") @Nullable String description,
                @JsonProperty("values") @NonNull List<JsonNode> values) {
            if (path.isBlank()) {
                throw new IllegalArgumentException("SweepParameter path cannot be blank");
            }
            if (description != null && description.isBlank()) {
                throw new IllegalArgumentException("SweepParameter description cannot be blank if present");
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
            this.path = path;
            this.description = description;
            this.values = List.copyOf(values);
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
                throw new IllegalArgumentException(
                        "HarnessRunOptions repeatCount must be positive if present: " + repeatCount);
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
                throw new IllegalArgumentException("ArtifactConfig outputDirectory cannot be blank if present");
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
                throw new IllegalArgumentException("ComparisonConfig baselineTrialId cannot be blank if present");
            }
            if (comparisonGroup != null && comparisonGroup.isBlank()) {
                throw new IllegalArgumentException("ComparisonConfig comparisonGroup cannot be blank if present");
            }
            if (purpose != null && purpose.isBlank()) {
                throw new IllegalArgumentException("ComparisonConfig purpose cannot be blank if present");
            }
        }
    }

    /// Provenance origin type for generated or manual trial configurations.
    public enum OriginType {
        MANUAL,
        SWEEP,
        SEARCH,
        EXTERNAL
    }

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
                throw new IllegalArgumentException(
                        "TrialOrigin candidateIndex must be >= 0 if present: " + candidateIndex);
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

    /// Configuration for an individual calibration trial run.
    public record TrialConfig(
            @Nullable String id,
            @Nullable String name,
            @Nullable String group,
            @Nullable String description,
            @Nullable String hypothesis,
            @Nullable ComparisonConfig comparison,
            @Nullable List<String> tags,
            @Nullable Map<String, String> labels,
            @Nullable Boolean enabled,
            @Nullable TrialOrigin origin,
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
                    null,
                    null,
                    forks,
                    warmups,
                    iterations,
                    jvmArgs,
                    calibrationConfig);
        }

        /// Convenience constructor for trial metadata without origin and labels.
        public TrialConfig(
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
            this(
                    id,
                    name,
                    group,
                    description,
                    hypothesis,
                    comparison,
                    tags,
                    null,
                    enabled,
                    null,
                    forks,
                    warmups,
                    iterations,
                    jvmArgs,
                    calibrationConfig);
        }

        /// Convenience constructor for trial metadata without labels.
        public TrialConfig(
                @Nullable String id,
                @Nullable String name,
                @Nullable String group,
                @Nullable String description,
                @Nullable String hypothesis,
                @Nullable ComparisonConfig comparison,
                @Nullable List<String> tags,
                @Nullable Boolean enabled,
                @Nullable TrialOrigin origin,
                int forks,
                int warmups,
                int iterations,
                @Nullable List<String> jvmArgs,
                @NonNull CalibrationBenchmarkConfig calibrationConfig) {
            this(
                    id,
                    name,
                    group,
                    description,
                    hypothesis,
                    comparison,
                    tags,
                    null,
                    enabled,
                    origin,
                    forks,
                    warmups,
                    iterations,
                    jvmArgs,
                    calibrationConfig);
        }

        /// Creates and validates a TrialConfig instance.
        ///
        /// @throws IllegalArgumentException if any non-null string metadata is blank or tags/labels/jvmArgs contain
        /// blank
        /// values
        /// @throws NullPointerException     if calibrationConfig is null or tags/jvmArgs contain null elements
        @JsonCreator
        public TrialConfig(
                @JsonProperty("id") @Nullable String id,
                @JsonProperty("name") @Nullable String name,
                @JsonProperty("group") @Nullable String group,
                @JsonProperty("description") @Nullable String description,
                @JsonProperty("hypothesis") @Nullable String hypothesis,
                @JsonProperty("comparison") @Nullable ComparisonConfig comparison,
                @JsonProperty("tags") @Nullable List<String> tags,
                @JsonProperty("labels") @Nullable Map<String, String> labels,
                @JsonProperty("enabled") @Nullable Boolean enabled,
                @JsonProperty("origin") @Nullable TrialOrigin origin,
                @JsonProperty("forks") int forks,
                @JsonProperty("warmups") int warmups,
                @JsonProperty("iterations") int iterations,
                @JsonProperty("jvmArgs") @Nullable List<String> jvmArgs,
                @JsonProperty("calibrationConfig") @NonNull CalibrationBenchmarkConfig calibrationConfig) {
            if (id != null && id.isBlank()) {
                throw new IllegalArgumentException("TrialConfig id cannot be blank if present");
            }
            if (name != null && name.isBlank()) {
                throw new IllegalArgumentException("TrialConfig name cannot be blank if present");
            }
            if (group != null && group.isBlank()) {
                throw new IllegalArgumentException("TrialConfig group cannot be blank if present");
            }
            if (description != null && description.isBlank()) {
                throw new IllegalArgumentException("TrialConfig description cannot be blank if present");
            }
            if (hypothesis != null && hypothesis.isBlank()) {
                throw new IllegalArgumentException("TrialConfig hypothesis cannot be blank if present");
            }
            if (tags != null) {
                for (String tag : tags) {
                    Objects.requireNonNull(tag, "TrialConfig tag element cannot be null");
                    if (tag.isBlank()) {
                        throw new IllegalArgumentException("TrialConfig tag element cannot be blank");
                    }
                }
                tags = List.copyOf(tags);
            }
            if (labels != null) {
                for (Map.Entry<String, String> entry : labels.entrySet()) {
                    String key = entry.getKey();
                    String val = entry.getValue();
                    if (key == null || key.isBlank()) {
                        throw new IllegalArgumentException("TrialConfig label key cannot be blank");
                    }
                    if (val == null || val.isBlank()) {
                        throw new IllegalArgumentException("TrialConfig label value cannot be blank for key: " + key);
                    }
                }
                labels = Map.copyOf(labels);
            }
            if (jvmArgs != null) {
                for (String jvmArg : jvmArgs) {
                    Objects.requireNonNull(jvmArg, "TrialConfig jvmArg element cannot be null");
                    if (jvmArg.isBlank()) {
                        throw new IllegalArgumentException("TrialConfig jvmArg element cannot be blank");
                    }
                }
                jvmArgs = List.copyOf(jvmArgs);
            }
            Objects.requireNonNull(calibrationConfig, "TrialConfig calibrationConfig cannot be null");
            this.id = id;
            this.name = name;
            this.group = group;
            this.description = description;
            this.hypothesis = hypothesis;
            this.comparison = comparison;
            this.tags = tags;
            this.labels = labels;
            this.enabled = enabled;
            this.origin = origin;
            this.forks = forks;
            this.warmups = warmups;
            this.iterations = iterations;
            this.jvmArgs = jvmArgs;
            this.calibrationConfig = calibrationConfig;
        }
    }
}
