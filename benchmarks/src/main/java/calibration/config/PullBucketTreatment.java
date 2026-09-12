package calibration.config;

import io.euhedral_execution.core.flow_control.PullBucketDivisionMode;
import java.util.Objects;

/// One pull-bucketing treatment applied to a single measurement iteration.
public record PullBucketTreatment(long target, PullBucketDivisionMode divisionMode) {

    public static final PullBucketTreatment BASELINE = new PullBucketTreatment(2_048L, PullBucketDivisionMode.FLOOR);

    public PullBucketTreatment {
        if (target <= 0L) {
            throw new IllegalArgumentException("Pull bucket target must be positive");
        }
        Objects.requireNonNull(divisionMode, "Pull bucket division mode must not be null");
    }

    public String id() {
        return divisionMode + "_" + target;
    }
}
