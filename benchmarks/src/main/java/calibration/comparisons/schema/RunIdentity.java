package calibration.comparisons.schema;

import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Structural identity for a completed calibration run invocation.
public record RunIdentity(
        @NonNull String trialId,
        @Nullable String trialName,
        @Nullable String trialGroup,
        int repeatIndex,
        @Nullable Integer forkIndex,
        @NonNull String sourcePath) {

    public RunIdentity {
        Objects.requireNonNull(trialId, "trialId must not be null");
        Objects.requireNonNull(sourcePath, "sourcePath must not be null");
        if (trialId.isBlank()) {
            throw new IllegalArgumentException("trialId must not be blank");
        }
        if (sourcePath.isBlank()) {
            throw new IllegalArgumentException("sourcePath must not be blank");
        }
    }
}
