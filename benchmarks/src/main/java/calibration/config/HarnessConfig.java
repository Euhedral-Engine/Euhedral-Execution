package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Configuration for executing and retaining a set of calibration trials.
public record HarnessConfig(
        @Nullable Integer schemaVersion,
        @Nullable String id,
        @Nullable String name,
        @Nullable String description,
        @Nullable Map<String, String> labels,
        @Nullable List<ProfileImport> imports,
        @Nullable HarnessRunOptions runOptions,
        @Nullable ArtifactConfig artifacts,
        @Nullable Map<String, CalibrationBenchmarkConfig> calibrationProfiles,
        @Nullable List<SweepConfig> sweeps,
        @NonNull List<TrialConfig> trials) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    @JsonCreator
    public HarnessConfig {
        if (schemaVersion != null && schemaVersion <= 0) {
            throw new IllegalArgumentException("HarnessConfig schemaVersion must be positive");
        }
        validateText(id, "id");
        validateText(name, "name");
        validateText(description, "description");

        if (labels != null) {
            for (Map.Entry<String, String> entry : labels.entrySet()) {
                validateText(entry.getKey(), "label key");
                validateText(entry.getValue(), "label value");
            }
            labels = Map.copyOf(labels);
        }

        if (imports != null) {
            Set<String> namespaces = new HashSet<>();
            for (ProfileImport declaration : imports) {
                Objects.requireNonNull(declaration, "HarnessConfig import element cannot be null");
                if (!namespaces.add(declaration.namespace())) {
                    throw new IllegalArgumentException("Duplicate import namespace: " + declaration.namespace());
                }
            }
            imports = List.copyOf(imports);
        }

        if (calibrationProfiles != null) {
            for (Map.Entry<String, CalibrationBenchmarkConfig> entry : calibrationProfiles.entrySet()) {
                validateText(entry.getKey(), "calibration profile name");
                Objects.requireNonNull(entry.getValue(), "Calibration profile cannot be null");
            }
            calibrationProfiles = Map.copyOf(calibrationProfiles);
        }

        Set<String> sweepIds = new HashSet<>();
        if (sweeps != null) {
            for (SweepConfig sweep : sweeps) {
                Objects.requireNonNull(sweep, "HarnessConfig sweep element cannot be null");
                if (!sweepIds.add(sweep.id())) {
                    throw new IllegalArgumentException("Duplicate sweep id: " + sweep.id());
                }
            }
            sweeps = List.copyOf(sweeps);
        }

        Objects.requireNonNull(trials, "HarnessConfig trials cannot be null");
        if (trials.isEmpty()) {
            throw new IllegalArgumentException("HarnessConfig trial configurations cannot be empty");
        }
        Set<String> trialIds = new HashSet<>();
        for (TrialConfig trial : trials) {
            Objects.requireNonNull(trial, "HarnessConfig trial element cannot be null");
            if (trial.id() != null && !trialIds.add(trial.id())) {
                throw new IllegalArgumentException("Duplicate trial id: " + trial.id());
            }
        }
        trials = List.copyOf(trials);

        if (sweeps != null) {
            for (SweepConfig sweep : sweeps) {
                if (!trialIds.contains(sweep.baseTrialId())) {
                    throw new IllegalArgumentException("Sweep base trial not found: " + sweep.baseTrialId());
                }
            }
        }
        for (TrialConfig trial : trials) {
            validateProfileReference(trial, calibrationProfiles, imports);
        }
    }

    private static void validateText(@Nullable String value, String field) {
        if (value == null || value.isBlank()) {
            if (value != null) {
                throw new IllegalArgumentException("HarnessConfig " + field + " cannot be blank");
            }
        }
    }

    private static void validateProfileReference(
            TrialConfig trial,
            @Nullable Map<String, CalibrationBenchmarkConfig> profiles,
            @Nullable List<ProfileImport> imports) {
        String reference = trial.calibrationProfile();
        if (reference == null || (profiles != null && profiles.containsKey(reference))) {
            return;
        }
        if (imports == null || !reference.contains(".")) {
            throw new IllegalArgumentException("Calibration profile not found: " + reference);
        }
        String namespace = reference.substring(0, reference.indexOf('.'));
        if (imports.stream().noneMatch(declaration -> declaration.namespace().equals(namespace))) {
            throw new IllegalArgumentException("Calibration profile namespace not imported: " + reference);
        }
    }

    public static HarnessConfig load(@NonNull File rootConfigFile, @NonNull ObjectMapper mapper) throws Exception {
        return ProfileLibraryLoader.loadAndResolve(rootConfigFile, mapper);
    }

    public HarnessConfig resolveCalibrationProfiles() {
        if (this.calibrationProfiles == null || this.calibrationProfiles.isEmpty()) {
            return this;
        }
        boolean changed = false;
        List<TrialConfig> resolved = new ArrayList<>(this.trials.size());
        for (TrialConfig trial : this.trials) {
            if (trial.calibrationProfile() != null && trial.calibrationConfig() == null) {
                CalibrationBenchmarkConfig profile = this.calibrationProfiles.get(trial.calibrationProfile());
                if (profile == null) {
                    throw new IllegalArgumentException("Calibration profile not found: " + trial.calibrationProfile());
                }
                resolved.add(trial.withCalibrationConfig(profile));
                changed = true;
            } else {
                resolved.add(trial);
            }
        }
        return changed
                ? new HarnessConfig(
                        this.schemaVersion,
                        this.id,
                        this.name,
                        this.description,
                        this.labels,
                        this.imports,
                        this.runOptions,
                        this.artifacts,
                        this.calibrationProfiles,
                        this.sweeps,
                        resolved)
                : this;
    }
}
