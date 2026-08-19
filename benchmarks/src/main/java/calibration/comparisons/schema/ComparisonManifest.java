package calibration.comparisons.schema;

import calibration.config.ComparisonStrategy;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Manifest describing post-run comparison metadata, strategy, resolved pairs, and exported artifacts.
public record ComparisonManifest(
        int schemaVersion,
        @NonNull ComparisonStrategy strategy,
        @Nullable List<String> keyPaths,
        int pairCount,
        @NonNull List<ComparisonPairManifestEntry> pairs,
        @NonNull List<String> unmatchedBaselineKeys,
        @NonNull List<String> unmatchedCandidateKeys,
        @NonNull List<String> exportedArtifacts) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public ComparisonManifest {
        Objects.requireNonNull(strategy, "strategy must not be null");
        keyPaths = keyPaths != null ? List.copyOf(keyPaths) : null;
        pairs = pairs != null ? List.copyOf(pairs) : List.of();
        unmatchedBaselineKeys = unmatchedBaselineKeys != null ? List.copyOf(unmatchedBaselineKeys) : List.of();
        unmatchedCandidateKeys = unmatchedCandidateKeys != null ? List.copyOf(unmatchedCandidateKeys) : List.of();
        exportedArtifacts = exportedArtifacts != null ? List.copyOf(exportedArtifacts) : List.of();
    }

    /// Single resolved comparison pair entry inside the manifest.
    public record ComparisonPairManifestEntry(
            int pairIndex,
            @Nullable String key,
            @NonNull RunIdentity baselineIdentity,
            @NonNull String baselineSourcePath,
            @NonNull RunArtifacts baselineArtifacts,
            @NonNull RunIdentity candidateIdentity,
            @NonNull String candidateSourcePath,
            @NonNull RunArtifacts candidateArtifacts,
            @NonNull CompatibilityStatus compatibilityStatus,
            @NonNull List<String> compatibilityReasons) {

        public ComparisonPairManifestEntry {
            Objects.requireNonNull(baselineIdentity, "baselineIdentity must not be null");
            Objects.requireNonNull(baselineSourcePath, "baselineSourcePath must not be null");
            Objects.requireNonNull(baselineArtifacts, "baselineArtifacts must not be null");
            Objects.requireNonNull(candidateIdentity, "candidateIdentity must not be null");
            Objects.requireNonNull(candidateSourcePath, "candidateSourcePath must not be null");
            Objects.requireNonNull(candidateArtifacts, "candidateArtifacts must not be null");
            Objects.requireNonNull(compatibilityStatus, "compatibilityStatus must not be null");
            compatibilityReasons = compatibilityReasons == null ? List.of() : List.copyOf(compatibilityReasons);
        }
    }
}
