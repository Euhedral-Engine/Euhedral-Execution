package calibration.statistics;

/// Two-dimensional gradient result with component derivatives and Euclidean magnitude.
public record GradientResult(double dx, double dy, double magnitude) {

    public static GradientResult of(double dx, double dy) {
        if (!Double.isFinite(dx) || !Double.isFinite(dy)) {
            return new GradientResult(dx, dy, Double.NaN);
        }
        return new GradientResult(dx, dy, Math.hypot(dx, dy));
    }
}
