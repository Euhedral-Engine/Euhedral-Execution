package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.util.ArrayList;
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
            if (trial.calibrationProfile() != null) {
                if (calibrationProfiles == null || !calibrationProfiles.containsKey(trial.calibrationProfile())) {
                    throw new IllegalArgumentException(
                            "Referenced calibrationProfile '" + trial.calibrationProfile()
                                    + "' was not found in calibrationProfiles");
                }
            }
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

    /// Resolves calibrationProfiles references across all trials, populating calibrationConfig where needed.
    ///
    /// @return a new HarnessConfig with all trial calibration profiles resolved, or this instance if no resolution was needed
    public HarnessConfig resolveCalibrationProfiles() {
        if (calibrationProfiles == null || calibrationProfiles.isEmpty()) {
            return this;
        }
        boolean modified = false;
        List<TrialConfig> resolvedTrials = new ArrayList<>(trials.size());
        for (TrialConfig trial : trials) {
            if (trial.calibrationProfile() != null && trial.calibrationConfig() == null) {
                CalibrationBenchmarkConfig profileConfig = calibrationProfiles.get(trial.calibrationProfile());
                if (profileConfig == null) {
                    throw new IllegalArgumentException("Referenced calibrationProfile '" + trial.calibrationProfile()
                            + "' was not found in calibrationProfiles");
                }
                resolvedTrials.add(trial.withCalibrationConfig(profileConfig));
                modified = true;
            } else {
                resolvedTrials.add(trial);
            }
        }
        if (!modified) {
            return this;
        }
        return new HarnessConfig(
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
                searches,
                resolvedTrials);
    }
}
