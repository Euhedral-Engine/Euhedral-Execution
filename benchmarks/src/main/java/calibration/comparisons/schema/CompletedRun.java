package calibration.comparisons.schema;

import calibration.config.TrialConfig;
import calibration.statistics.fork.SystemForkResult;
import calibration.statistics.iteration.CoreIterationResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// Immutable model representing one fully loaded completed calibration run.
public record CompletedRun(
        @NonNull RunIdentity identity,
        @NonNull TrialConfig trialConfig,
        @NonNull ThroughputResult throughput,
        @NonNull SystemForkResult system,
        @NonNull List<List<CoreIterationResult>> iterations,
        @NonNull RunArtifacts artifacts) {

    public CompletedRun {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(trialConfig, "trialConfig must not be null");
        Objects.requireNonNull(throughput, "throughput must not be null");
        Objects.requireNonNull(system, "system must not be null");
        Objects.requireNonNull(artifacts, "artifacts must not be null");

        if (iterations != null) {
            List<List<CoreIterationResult>> outerCopy = new ArrayList<>(iterations.size());
            for (List<CoreIterationResult> inner : iterations) {
                outerCopy.add(inner != null ? List.copyOf(inner) : List.of());
            }
            iterations = Collections.unmodifiableList(outerCopy);
        } else {
            iterations = List.of();
        }
    }

    public CompletedRun(
            @NonNull RunIdentity identity,
            @NonNull TrialConfig trialConfig,
            @NonNull ThroughputResult throughput,
            @NonNull List<List<CoreIterationResult>> iterations,
            @NonNull RunArtifacts artifacts) {
        this(identity, trialConfig, throughput, SystemForkResult.EMPTY, iterations, artifacts);
    }
}
