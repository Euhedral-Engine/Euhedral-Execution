package calibration.comparisons.schema;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Manifest describing comparison metadata, baseline/candidate identities, source artifacts, and exported files.
public record ComparisonManifest(
        int schemaVersion,
        @NonNull RunIdentity baselineIdentity,
        @NonNull String baselineSourcePath,
        @NonNull RunArtifacts baselineArtifacts,
        @NonNull List<CandidateManifestEntry> candidates,
        @NonNull List<String> exportedArtifacts) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public ComparisonManifest {
        Objects.requireNonNull(baselineIdentity, "baselineIdentity must not be null");
        Objects.requireNonNull(baselineSourcePath, "baselineSourcePath must not be null");
        Objects.requireNonNull(baselineArtifacts, "baselineArtifacts must not be null");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        exportedArtifacts = exportedArtifacts == null ? List.of() : List.copyOf(exportedArtifacts);
    }

    /// Single candidate entry inside the comparison manifest.
    public record CandidateManifestEntry(
            @NonNull RunIdentity identity,
            @NonNull String sourcePath,
            @NonNull RunArtifacts artifacts,
            @NonNull CompatibilityStatus compatibilityStatus,
            @NonNull List<String> compatibilityReasons) {

        public CandidateManifestEntry {
            Objects.requireNonNull(identity, "identity must not be null");
            Objects.requireNonNull(sourcePath, "sourcePath must not be null");
            Objects.requireNonNull(artifacts, "artifacts must not be null");
            Objects.requireNonNull(compatibilityStatus, "compatibilityStatus must not be null");
            compatibilityReasons = compatibilityReasons == null ? List.of() : List.copyOf(compatibilityReasons);
        }
    }
}
