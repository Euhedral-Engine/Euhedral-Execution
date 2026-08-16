package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
        @Nullable String warmupTime,
        @Nullable String measurementTime,
        @Nullable List<String> jvmArgs,
        @Nullable String calibrationProfile,
        @Nullable CalibrationBenchmarkConfig calibrationConfig) {

    /// Convenience constructor for execution properties with calibrationConfig without trial metadata.
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
                null,
                null,
                jvmArgs,
                null,
                calibrationConfig);
    }

    /// Convenience constructor for execution properties with calibrationProfile without trial metadata.
    public TrialConfig(
            int forks,
            int warmups,
            int iterations,
            @Nullable List<String> jvmArgs,
            @NonNull String calibrationProfile) {
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
                null,
                null,
                jvmArgs,
                calibrationProfile,
                null);
    }

    /// Convenience constructor for execution properties with warmupTime, measurementTime, and calibrationConfig.
    public TrialConfig(
            int forks,
            int warmups,
            int iterations,
            @Nullable String warmupTime,
            @Nullable String measurementTime,
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
                warmupTime,
                measurementTime,
                jvmArgs,
                null,
                calibrationConfig);
    }

    /// Convenience constructor for execution properties with warmupTime, measurementTime, and calibrationProfile.
    public TrialConfig(
            int forks,
            int warmups,
            int iterations,
            @Nullable String warmupTime,
            @Nullable String measurementTime,
            @Nullable List<String> jvmArgs,
            @NonNull String calibrationProfile) {
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
                warmupTime,
                measurementTime,
                jvmArgs,
                calibrationProfile,
                null);
    }

    /// Convenience constructor for trial metadata without origin, labels, and with calibrationConfig.
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
                null,
                null,
                jvmArgs,
                null,
                calibrationConfig);
    }

    /// Convenience constructor for trial metadata without origin, labels, and with calibrationProfile.
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
            @NonNull String calibrationProfile) {
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
                null,
                null,
                jvmArgs,
                calibrationProfile,
                null);
    }

    /// Convenience constructor for trial metadata without labels and with calibrationConfig.
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
                null,
                null,
                jvmArgs,
                null,
                calibrationConfig);
    }

    /// Convenience constructor for trial metadata without labels and with calibrationProfile.
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
            @NonNull String calibrationProfile) {
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
                null,
                null,
                jvmArgs,
                calibrationProfile,
                null);
    }

    /// Convenience constructor for trial metadata with labels, origin, and calibrationConfig.
    public TrialConfig(
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
        this(
                id,
                name,
                group,
                description,
                hypothesis,
                comparison,
                tags,
                labels,
                enabled,
                origin,
                forks,
                warmups,
                iterations,
                null,
                null,
                jvmArgs,
                null,
                calibrationConfig);
    }

    /// Convenience constructor for trial metadata with labels, origin, and calibrationProfile.
    public TrialConfig(
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
            @NonNull String calibrationProfile) {
        this(
                id,
                name,
                group,
                description,
                hypothesis,
                comparison,
                tags,
                labels,
                enabled,
                origin,
                forks,
                warmups,
                iterations,
                null,
                null,
                jvmArgs,
                calibrationProfile,
                null);
    }

    /// Convenience constructor for trial metadata with all fields except calibrationProfile.
    public TrialConfig(
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
            @Nullable String warmupTime,
            @Nullable String measurementTime,
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
                labels,
                enabled,
                origin,
                forks,
                warmups,
                iterations,
                warmupTime,
                measurementTime,
                jvmArgs,
                null,
                calibrationConfig);
    }

    /// Convenience constructor for trial metadata with all fields except calibrationConfig.
    public TrialConfig(
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
            @Nullable String warmupTime,
            @Nullable String measurementTime,
            @Nullable List<String> jvmArgs,
            @NonNull String calibrationProfile) {
        this(
                id,
                name,
                group,
                description,
                hypothesis,
                comparison,
                tags,
                labels,
                enabled,
                origin,
                forks,
                warmups,
                iterations,
                warmupTime,
                measurementTime,
                jvmArgs,
                calibrationProfile,
                null);
    }

    /// Creates and validates a TrialConfig instance.
    ///
    /// @throws IllegalArgumentException if any non-null string metadata is blank, tags/labels/jvmArgs contain blank
    /// values, or neither calibrationConfig nor calibrationProfile is provided
    /// @throws NullPointerException     if tags/jvmArgs contain null elements
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
            @JsonProperty("warmupTime") @Nullable String warmupTime,
            @JsonProperty("measurementTime") @Nullable String measurementTime,
            @JsonProperty("jvmArgs") @Nullable List<String> jvmArgs,
            @JsonProperty("calibrationProfile") @Nullable String calibrationProfile,
            @JsonProperty("calibrationConfig") @Nullable CalibrationBenchmarkConfig calibrationConfig) {
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
        if (warmupTime != null && warmupTime.isBlank()) {
            throw new IllegalArgumentException("TrialConfig warmupTime cannot be blank if present");
        }
        if (measurementTime != null && measurementTime.isBlank()) {
            throw new IllegalArgumentException("TrialConfig measurementTime cannot be blank if present");
        }
        if (calibrationProfile != null && calibrationProfile.isBlank()) {
            throw new IllegalArgumentException("TrialConfig calibrationProfile cannot be blank if present");
        }
        if (calibrationConfig == null && calibrationProfile == null) {
            throw new IllegalArgumentException(
                    "TrialConfig must specify either calibrationConfig or calibrationProfile");
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
        this.warmupTime = warmupTime;
        this.measurementTime = measurementTime;
        this.jvmArgs = jvmArgs;
        this.calibrationProfile = calibrationProfile;
        this.calibrationConfig = calibrationConfig;
    }

    /// Returns a copy of this TrialConfig with the given calibrationConfig set.
    public TrialConfig withCalibrationConfig(@NonNull CalibrationBenchmarkConfig calibrationConfig) {
        Objects.requireNonNull(calibrationConfig, "TrialConfig calibrationConfig cannot be null");
        return new TrialConfig(
                this.id,
                this.name,
                this.group,
                this.description,
                this.hypothesis,
                this.comparison,
                this.tags,
                this.labels,
                this.enabled,
                this.origin,
                this.forks,
                this.warmups,
                this.iterations,
                this.warmupTime,
                this.measurementTime,
                this.jvmArgs,
                this.calibrationProfile,
                calibrationConfig);
    }

    /// Returns a copy of this TrialConfig with the given calibrationProfile reference set.
    public TrialConfig withCalibrationProfile(@Nullable String calibrationProfile) {
        return new TrialConfig(
                this.id,
                this.name,
                this.group,
                this.description,
                this.hypothesis,
                this.comparison,
                this.tags,
                this.labels,
                this.enabled,
                this.origin,
                this.forks,
                this.warmups,
                this.iterations,
                this.warmupTime,
                this.measurementTime,
                this.jvmArgs,
                calibrationProfile,
                this.calibrationConfig);
    }
}
