package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.euhedral_execution.core.config.FragmentDecisionWeights;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// Dedicated container for reusable external calibration profiles and decision weight profiles.
/// Profile libraries contain only imports, calibrationProfiles, and decisionWeightProfiles.
public record ProfileLibrary(
        @Nullable List<ProfileImport> imports,
        @Nullable Map<String, CalibrationBenchmarkConfig> calibrationProfiles,
        @Nullable Map<String, FragmentDecisionWeights> decisionWeightProfiles) {

    /// Convenience constructor for profile library without imports.
    public ProfileLibrary(
            @Nullable Map<String, CalibrationBenchmarkConfig> calibrationProfiles,
            @Nullable Map<String, FragmentDecisionWeights> decisionWeightProfiles) {
        this(null, calibrationProfiles, decisionWeightProfiles);
    }

    /// Creates and validates a ProfileLibrary instance.
    ///
    /// @throws IllegalArgumentException if namespace is invalid/duplicate or profile names are blank
    /// @throws NullPointerException     if imports or profile maps contain null values
    @JsonCreator
    public ProfileLibrary {
        if (imports != null) {
            Set<String> declaredNamespaces = new HashSet<>();
            for (ProfileImport imp : imports) {
                Objects.requireNonNull(imp, "ProfileLibrary import element cannot be null");
                if (!declaredNamespaces.add(imp.namespace())) {
                    throw new IllegalArgumentException(
                            "Duplicate import namespace '" + imp.namespace() + "' declared in profile library");
                }
            }
            imports = List.copyOf(imports);
        }
        if (calibrationProfiles != null) {
            for (Map.Entry<String, CalibrationBenchmarkConfig> entry : calibrationProfiles.entrySet()) {
                String profileName = entry.getKey();
                if (profileName == null || profileName.isBlank()) {
                    throw new IllegalArgumentException("ProfileLibrary calibrationProfiles key cannot be blank");
                }
                Objects.requireNonNull(
                        entry.getValue(),
                        "ProfileLibrary calibrationProfiles value cannot be null for key: " + profileName);
            }
            calibrationProfiles = Map.copyOf(calibrationProfiles);
        }
        if (decisionWeightProfiles != null) {
            for (Map.Entry<String, FragmentDecisionWeights> entry : decisionWeightProfiles.entrySet()) {
                String profileName = entry.getKey();
                if (profileName == null || profileName.isBlank()) {
                    throw new IllegalArgumentException("ProfileLibrary decisionWeightProfiles key cannot be blank");
                }
                Objects.requireNonNull(
                        entry.getValue(),
                        "ProfileLibrary decisionWeightProfiles value cannot be null for key: " + profileName);
            }
            decisionWeightProfiles = Map.copyOf(decisionWeightProfiles);
        }
    }
}
