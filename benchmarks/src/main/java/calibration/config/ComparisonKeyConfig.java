package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/// Configuration defining how comparison keys are extracted and matched in KEYED comparison mode.
public record ComparisonKeyConfig(@NonNull List<String> paths, boolean requireCompleteMatch) {

    public ComparisonKeyConfig {
        Objects.requireNonNull(paths, "paths must not be null");
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("ComparisonKeyConfig must specify at least one non-blank key path");
        }
        for (String p : paths) {
            Objects.requireNonNull(p, "Key path element must not be null");
            if (p.isBlank()) {
                throw new IllegalArgumentException("Key path element must not be blank");
            }
        }
        paths = List.copyOf(paths);
    }

    @JsonCreator
    public static ComparisonKeyConfig fromJson(
            @JsonProperty("paths") @Nullable List<String> paths,
            @JsonProperty("path") @Nullable String path,
            @JsonProperty("requireCompleteMatch") @Nullable Boolean requireCompleteMatch) {
        List<String> resolvedPaths = new ArrayList<>();
        if (paths != null && !paths.isEmpty()) {
            for (String p : paths) {
                Objects.requireNonNull(p, "Key path element must not be null");
                if (p.isBlank()) {
                    throw new IllegalArgumentException("Key path element must not be blank");
                }
                resolvedPaths.add(p.trim());
            }
        } else if (path != null && !path.isBlank()) {
            resolvedPaths.add(path.trim());
        }

        if (resolvedPaths.isEmpty()) {
            throw new IllegalArgumentException("ComparisonKeyConfig must specify at least one non-blank key path");
        }

        return new ComparisonKeyConfig(resolvedPaths, requireCompleteMatch != null ? requireCompleteMatch : true);
    }

    public ComparisonKeyConfig(@NonNull List<String> paths) {
        this(paths, true);
    }

    public static ComparisonKeyConfig ofPath(@NonNull String path) {
        return new ComparisonKeyConfig(List.of(path), true);
    }

    public static ComparisonKeyConfig ofPaths(@NonNull List<String> paths) {
        return new ComparisonKeyConfig(paths, true);
    }
}
