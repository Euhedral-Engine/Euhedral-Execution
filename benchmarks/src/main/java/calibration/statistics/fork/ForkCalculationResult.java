package calibration.statistics.fork;

import calibration.statistics.iteration.IterationResult;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Complete calculation container for an independent JMH benchmark fork.
/// Pairs the authoritative whole-fork SystemForkResult summary with diagnostic per-iteration IterationResult list.
public record ForkCalculationResult(
        @NonNull SystemForkResult system, @NonNull List<IterationResult> iterations) {

    public ForkCalculationResult {
        Objects.requireNonNull(system, "system must not be null");
        iterations = iterations != null ? List.copyOf(iterations) : List.of();
    }
}
