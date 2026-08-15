package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Configuration for benchmark calibration harness execution.
/// Holds optional metadata, run options, and non-empty trial specifications.
public record HarnessConfig(
        @Nullable Integer schemaVersion,
        @Nullable String id,
        @Nullable String name,
        @Nullable String description,
        @Nullable Map<String, String> labels,
        @Nullable HarnessRunOptions runOptions,
        @NonNull List<TrialConfig> trials) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /// Convenience constructor for harness configs containing only trials.
    public HarnessConfig(@NonNull List<TrialConfig> trials) {
        this(null, null, null, null, null, null, trials);
    }

    /// Convenience constructor for harness configs without runOptions.
    public HarnessConfig(
            @Nullable Integer schemaVersion,
            @Nullable String id,
            @Nullable String name,
            @Nullable String description,
            @Nullable Map<String, String> labels,
            @NonNull List<TrialConfig> trials) {
        this(schemaVersion, id, name, description, labels, null, trials);
    }

    /// Creates and validates a HarnessConfig instance.
    ///
    /// @throws IllegalArgumentException if schemaVersion is non-positive, id or name is blank,
    ///                                  trials is empty, non-null trial IDs are duplicated,
    ///                                  or referenced baselineTrialIds are invalid/self-referential
    /// @throws NullPointerException     if trials is null
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
