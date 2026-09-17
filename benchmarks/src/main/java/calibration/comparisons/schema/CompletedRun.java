package calibration.comparisons.schema;

import calibration.config.TrialConfig;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

/// A completed benchmark run with the configuration and JMH fork scores needed for comparison.
public record CompletedRun(
        @NonNull RunIdentity identity,
        @NonNull TrialConfig trialConfig,
        @NonNull ThroughputResult throughput) {

    public CompletedRun {
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(trialConfig, "trialConfig must not be null");
        Objects.requireNonNull(throughput, "throughput must not be null");
    }
}
