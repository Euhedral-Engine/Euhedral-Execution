package calibration.statistics.iteration;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Aggregated calibration iteration result pairing whole-system summary with per-core diagnostic results.
public record IterationResult(
        int iterationIndex,
        @NonNull SystemIterationResult system,
        @NonNull List<CoreIterationResult> cores) {

    public IterationResult {
        Objects.requireNonNull(system, "system must not be null");
        if (cores != null) {
            cores = List.copyOf(cores);
        } else {
            cores = List.of();
        }
    }
}
