package calibration;

import calibration.config.CalibrationLifecycleMode;
import java.util.Objects;

/// Applies the configured physical reset policy around one JMH iteration while leaving measurement segmentation
/// independent of scheduler state.
final class CalibrationIterationLifecycle {

    private final CalibrationLifecycleMode mode;

    CalibrationIterationLifecycle(CalibrationLifecycleMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    void beforeWindow(Runnable resetPhysicalState, Runnable startMeasurement) {
        Objects.requireNonNull(resetPhysicalState, "resetPhysicalState must not be null");
        Objects.requireNonNull(startMeasurement, "startMeasurement must not be null");
        if (this.mode == CalibrationLifecycleMode.RESET) {
            resetPhysicalState.run();
        }
        startMeasurement.run();
    }

    void afterWindow(Runnable resetPhysicalState) {
        Objects.requireNonNull(resetPhysicalState, "resetPhysicalState must not be null");
        if (this.mode == CalibrationLifecycleMode.RESET) {
            resetPhysicalState.run();
        }
    }
}
