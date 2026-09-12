package calibration.statistics.iteration;

/// Measurement-local timing and feeding evidence for one window in a persistent calibration trajectory.
public record TrajectoryWindow(
        long jvmId,
        int windowIndex,
        long trajectoryElapsedNanos,
        long windowElapsedNanos,
        long completedExecutions,
        double throughputExecutionsPerSecond,
        boolean continuouslyFed) {

    public TrajectoryWindow {
        if (jvmId < 0L) {
            throw new IllegalArgumentException("jvmId must not be negative");
        }
        if (windowIndex < 0) {
            throw new IllegalArgumentException("windowIndex must not be negative");
        }
        if (trajectoryElapsedNanos < 0L || windowElapsedNanos <= 0L || completedExecutions < 0L) {
            throw new IllegalArgumentException("trajectory/window timing and execution counts are invalid");
        }
        if (!Double.isFinite(throughputExecutionsPerSecond) || throughputExecutionsPerSecond < 0.0) {
            throw new IllegalArgumentException("throughputExecutionsPerSecond must be finite and non-negative");
        }
    }
}
