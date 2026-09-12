package calibration.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;
import org.junit.jupiter.api.Test;

class FiniteDifferencesTest {

    private static final double EPSILON = 1e-6;

    @Test
    void testCentralDifference() {
        // f(x) = 3x^2 + 2x + 1
        // f'(x) = 6x + 2 -> f'(2) = 14
        DoubleUnaryOperator f = x -> 3 * x * x + 2 * x + 1;
        double x = 2.0;
        double delta = 1e-4;

        double derivative = FiniteDifferences.centralDifference(f, x, delta);
        assertEquals(14.0, derivative, EPSILON);

        double direct =
                FiniteDifferences.centralDifference(f.applyAsDouble(x + delta), f.applyAsDouble(x - delta), delta);
        assertEquals(14.0, direct, EPSILON);
    }

    @Test
    void testForwardDifference() {
        // f(x) = 2x + 5 -> f'(x) = 2
        DoubleUnaryOperator f = x -> 2 * x + 5;
        double x = 3.0;
        double delta = 0.01;

        double derivative = FiniteDifferences.forwardDifference(f, x, delta);
        assertEquals(2.0, derivative, EPSILON);

        double direct = FiniteDifferences.forwardDifference(f.applyAsDouble(x + delta), f.applyAsDouble(x), delta);
        assertEquals(2.0, direct, EPSILON);
    }

    @Test
    void testBackwardDifference() {
        // f(x) = 2x + 5 -> f'(x) = 2
        DoubleUnaryOperator f = x -> 2 * x + 5;
        double x = 3.0;
        double delta = 0.01;

        double derivative = FiniteDifferences.backwardDifference(f, x, delta);
        assertEquals(2.0, derivative, EPSILON);

        double direct = FiniteDifferences.backwardDifference(f.applyAsDouble(x), f.applyAsDouble(x - delta), delta);
        assertEquals(2.0, direct, EPSILON);
    }

    @Test
    void testGradient2D() {
        // f(x, y) = x^2 + y^2
        // grad f = (2x, 2y) at (3, 4) -> (6, 8)
        // magnitude = sqrt(6^2 + 8^2) = 10
        DoubleBinaryOperator f = (x, y) -> x * x + y * y;
        double x = 3.0;
        double y = 4.0;
        double deltaX = 1e-4;
        double deltaY = 1e-4;

        GradientResult result = FiniteDifferences.gradient2D(f, x, y, deltaX, deltaY);
        assertEquals(6.0, result.dx(), EPSILON);
        assertEquals(8.0, result.dy(), EPSILON);
        assertEquals(10.0, result.magnitude(), EPSILON);

        GradientResult direct = FiniteDifferences.gradient(6.0, 8.0);
        assertEquals(10.0, direct.magnitude(), 1e-9);
    }

    @Test
    void testDeltaValidation() {
        assertThrows(IllegalArgumentException.class, () -> FiniteDifferences.centralDifference(1.0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> FiniteDifferences.forwardDifference(1.0, 0.0, Double.NaN));
        assertThrows(
                IllegalArgumentException.class,
                () -> FiniteDifferences.backwardDifference(1.0, 0.0, Double.POSITIVE_INFINITY));
    }
}
