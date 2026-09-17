package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Configuration for one JMH calibration trial.
public record TrialConfig(
        @Nullable String id,
        @Nullable String name,
        @Nullable String group,
        @Nullable String description,
        @Nullable String hypothesis,
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
                forks,
                warmups,
                iterations,
                null,
                null,
                jvmArgs,
                null,
                calibrationConfig);
    }

    @JsonCreator
    public TrialConfig {
        validateText(id, "id");
        validateText(name, "name");
        validateText(group, "group");
        validateText(description, "description");
        validateText(hypothesis, "hypothesis");
        validateText(warmupTime, "warmupTime");
        validateText(measurementTime, "measurementTime");
        validateText(calibrationProfile, "calibrationProfile");
        if (calibrationConfig == null && calibrationProfile == null) {
            throw new IllegalArgumentException("TrialConfig must specify calibrationConfig or calibrationProfile");
        }
        tags = copyStrings(tags, "tag");
        jvmArgs = copyStrings(jvmArgs, "jvmArg");
        if (labels != null) {
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                validateText(entry.getKey(), "label key");
                validateText(entry.getValue(), "label value");
            }
            labels = Map.copyOf(labels);
        }
        origin = origin == null ? new TrialOrigin(OriginType.MANUAL) : origin;
    }

    private static void validateText(@Nullable String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException("TrialConfig " + field + " cannot be blank if present");
        }
    }

    private static @Nullable List<String> copyStrings(@Nullable List<String> values, String field) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            Objects.requireNonNull(value, "TrialConfig " + field + " element cannot be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException("TrialConfig " + field + " element cannot be blank");
            }
        }
        return List.copyOf(values);
    }

    public TrialConfig withCalibrationConfig(@NonNull CalibrationBenchmarkConfig calibrationConfig) {
        return new TrialConfig(
                this.id,
                this.name,
                this.group,
                this.description,
                this.hypothesis,
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
                Objects.requireNonNull(calibrationConfig, "calibrationConfig must not be null"));
    }
}
