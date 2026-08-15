package calibration.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public record HarnessConfig(List<TrialConfig> trials) {
    @JsonCreator
    public HarnessConfig {
        Objects.requireNonNull(trials);
        if (trials.isEmpty()) {
            throw new IllegalArgumentException("Trial configurations can not be empty");
        }
    }

    public record TrialConfig(
            int forks,
            int warmups,
            int iterations,
            @Nullable List<String> jvmArgs,
            @NonNull CalibrationBenchmarkConfig calibrationConfig) {
        @JsonCreator
        public TrialConfig {
            Objects.requireNonNull(calibrationConfig);
        }
    }
}
