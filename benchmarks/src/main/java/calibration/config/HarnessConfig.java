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
/// Holds optional metadata and non-empty trial specifications.
public record HarnessConfig(
        @Nullable Integer schemaVersion,
        @Nullable String id,
        @Nullable String name,
        @Nullable String description,
        @Nullable Map<String, String> labels,
        @NonNull List<TrialConfig> trials) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /// Convenience constructor for harness configs containing only trials.
    public HarnessConfig(@NonNull List<TrialConfig> trials) {
        this(null, null, null, null, null, trials);
    }

    /// Creates and validates a HarnessConfig instance.
    ///
    /// @throws IllegalArgumentException if schemaVersion is non-positive, id or name is blank,
    ///                                  trials is empty, or non-null trial IDs are duplicated
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
    }

    /// Configuration for an individual calibration trial run.
    public record TrialConfig(
            @Nullable String id,
            @Nullable String name,
            @Nullable String group,
            @Nullable String description,
            @Nullable String hypothesis,
            @Nullable String baselineTrialId,
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
            if (baselineTrialId != null && baselineTrialId.isBlank()) {
                throw new IllegalArgumentException("trial baselineTrialId cannot be blank if present");
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
