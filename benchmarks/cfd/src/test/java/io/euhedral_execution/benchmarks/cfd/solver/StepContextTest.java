package io.euhedral_execution.benchmarks.cfd.solver;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.Guards;
import org.junit.jupiter.api.Test;

class StepContextTest {
    @Test
    void squaredMachGuardAgreesWithMagnitudeAcrossThresholdsAndExtremeScales() {
        for (double limit : new double[] {0.1, 0.3, 1, 1e-300, 1e-160, 1e160, Double.MAX_VALUE}) {
            var guards = new Guards(limit, .1);
            for (double[] direction : new double[][] {{1, 0, 0}, {0, -1, 0}, {.6, .8, 0}, {1, -2, 3}}) {
                double length = Math.hypot(Math.hypot(direction[0], direction[1]), direction[2]);
                for (double factor : new double[] {0, .5, 1 - 1e-12, 1 + 1e-12, 2}) {
                    double scale = (limit / Math.sqrt(3)) * factor;
                    double x = scale * (direction[0] / length);
                    double y = scale * (direction[1] / length);
                    double z = scale * (direction[2] / length);
                    double mach = Math.hypot(Math.hypot(x, y), z) * Math.sqrt(3);
                    if (!Double.isFinite(mach) || mach > limit) {
                        assertThrows(
                                SimulationException.class,
                                () -> StepContext.validateFields(guards, 1, 7, 1, x, y, z, 2, 3, 4));
                    } else {
                        assertDoesNotThrow(() -> StepContext.validateFields(guards, 1, 7, 1, x, y, z, 2, 3, 4));
                    }
                }
            }
        }
        assertDoesNotThrow(() -> StepContext.validateFields(new Guards(Math.sqrt(3), .1), 1, 1, 1, 1, 0, 0, 0, 0, 0));
    }

    @Test
    void nonFiniteVelocityAndInvalidDensityStillFailWithCoordinates() {
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (int axis = 0; axis < 3; axis++) {
                double[] v = new double[3];
                v[axis] = invalid;
                var error = assertThrows(
                        SimulationException.class,
                        () -> StepContext.validateFields(Guards.DEFAULT, 1, 7, 1, v[0], v[1], v[2], 2, 3, 4));
                assertEquals(7, error.step());
                assertTrue(error.getMessage().contains("Mach"));
            }
        }
        for (double rho : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, .8, 1.2}) {
            assertThrows(
                    SimulationException.class,
                    () -> StepContext.validateFields(Guards.DEFAULT, 1, 7, rho, 0, 0, 0, 2, 3, 4));
        }
        assertDoesNotThrow(
                () -> StepContext.validateFields(new Guards(Double.MAX_VALUE, .1), 1, 7, 1, 1e200, 0, 0, 2, 3, 4));
    }
}
