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

    public static final int CURRENT_SCHEMA_VERSION = 3;

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
            @NonNull RunIdentity candidateIdentity) {

        public ComparisonPairManifestEntry {
            Objects.requireNonNull(baselineIdentity, "baselineIdentity must not be null");
            Objects.requireNonNull(candidateIdentity, "candidateIdentity must not be null");
        }
    }
}
