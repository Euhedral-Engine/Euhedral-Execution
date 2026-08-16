package calibration.statistics;

import java.util.Objects;
import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;
import org.jspecify.annotations.NonNull;

/// Finite-difference approximations for derivatives and gradients in sweep analysis.
public final class FiniteDifferences {

    private FiniteDifferences() {}

    /// Central difference approximation: (f(x + delta) - f(x - delta)) / (2 * delta).
    public static double centralDifference(double fPlus, double fMinus, double delta) {
        validateDelta(delta);
        return (fPlus - fMinus) / (2.0 * delta);
    }

    /// Forward difference approximation for endpoints: (f(x + delta) - f(x)) / delta.
    public static double forwardDifference(double fPlus, double fCurrent, double delta) {
        validateDelta(delta);
        return (fPlus - fCurrent) / delta;
    }

    /// Backward difference approximation for endpoints: (f(x) - f(x - delta)) / delta.
    public static double backwardDifference(double fCurrent, double fMinus, double delta) {
        validateDelta(delta);
        return (fCurrent - fMinus) / delta;
    }

    /// Central difference approximation using a function.
    public static double centralDifference(@NonNull DoubleUnaryOperator f, double x, double delta) {
        Objects.requireNonNull(f, "Function f must not be null");
        validateDelta(delta);
        return centralDifference(f.applyAsDouble(x + delta), f.applyAsDouble(x - delta), delta);
    }

    /// Forward difference approximation using a function.
    public static double forwardDifference(@NonNull DoubleUnaryOperator f, double x, double delta) {
        Objects.requireNonNull(f, "Function f must not be null");
        validateDelta(delta);
        return forwardDifference(f.applyAsDouble(x + delta), f.applyAsDouble(x), delta);
    }

    /// Backward difference approximation using a function.
    public static double backwardDifference(@NonNull DoubleUnaryOperator f, double x, double delta) {
        Objects.requireNonNull(f, "Function f must not be null");
        validateDelta(delta);
        return backwardDifference(f.applyAsDouble(x), f.applyAsDouble(x - delta), delta);
    }

    /// Gradient result from precomputed directional derivatives dx and dy.
    public static GradientResult gradient(double dx, double dy) {
        return GradientResult.of(dx, dy);
    }

    /// Gradient approximation for a 2-parameter function at (x, y) using central differences.
    public static GradientResult gradient2D(
            @NonNull DoubleBinaryOperator f, double x, double y, double deltaX, double deltaY) {
        Objects.requireNonNull(f, "Function f must not be null");
        validateDelta(deltaX);
        validateDelta(deltaY);
        double dx = centralDifference(f.applyAsDouble(x + deltaX, y), f.applyAsDouble(x - deltaX, y), deltaX);
        double dy = centralDifference(f.applyAsDouble(x, y + deltaY), f.applyAsDouble(x, y - deltaY), deltaY);
        return GradientResult.of(dx, dy);
    }

    private static void validateDelta(double delta) {
        if (delta == 0.0 || !Double.isFinite(delta)) {
            throw new IllegalArgumentException("delta must be non-zero and finite, got: " + delta);
        }
    }
}
