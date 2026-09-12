package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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
