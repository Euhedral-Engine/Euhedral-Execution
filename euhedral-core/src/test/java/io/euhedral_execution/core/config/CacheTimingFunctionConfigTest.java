package io.euhedral_execution.core.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CacheTimingFunctionConfigTest {
    static CacheTimingFunctionConfig function(List<Double> park, List<Double> halfLife) {
        return new CacheTimingFunctionConfig(
                "bounded-v1",
                List.of(0.5, 2.0, 8.0),
                List.of(0.5, 2.0, 8.0),
                List.of(0.0, 0.0, 0.0),
                List.of(1.0, 4.0, 16.0),
                park,
                halfLife,
                15_000,
                1_000_000,
                0,
                1_000_000,
                250_000,
                2_000_000);
    }

    @Test
    void zeroCoefficientsPreserveBaselineIncludingPhrAboveOne() {
        var model = function(List.of(0.0, 0.0, 0.0, 0.0), List.of(0.0, 0.0, 0.0, 0.0));
        for (double c : new double[] {0, 0.2, 1}) {
            for (double p : new double[] {0, 1, 2, 4}) {
                assertEquals(15_000, model.parkNanos(c, p, 200, 7));
                assertEquals(1_000_000, model.halfLifeNanos(c, p, 200, 7));
            }
        }
        assertNull(new CacheTimingConfig(15_000, 1_000_000).function());
    }

    @Test
    void invalidAndUnsupportedInputsFallBackTogether() {
        var model = function(List.of(0.0, 0.0, 0.0, 0.0), List.of(0.0, 0.0, 0.0, 0.0));
        for (double[] input : new double[][] {
            {Double.NaN, 1, 0}, {0, 5, 0}, {0, -1, 0}, {0, 1, -0.5}, {0, 1, Double.POSITIVE_INFINITY}, {2, 1, 0}
        }) {
            assertEquals(17, model.parkNanos(input[0], input[1], input[2], 17));
            assertEquals(19, model.halfLifeNanos(input[0], input[1], input[2], 19));
        }
    }

    @Test
    void usesLogBodyAndFixedNormalizationWithIndependentBoundedOutputs() {
        var model = function(List.of(0.0, 0.0, 0.0, 1.0), List.of(0.0, 1.0, 0.0, 0.0));
        assertEquals(Math.round(15_000 * Math.exp(-0.5)), model.parkNanos(1, 2, Math.expm1(4), 7));
        assertEquals(2_000_000, model.halfLifeNanos(1, 2, Math.expm1(4), 7));
        var extreme = function(List.of(-1000.0, 0.0, 0.0, 0.0), List.of(1000.0, 0.0, 0.0, 0.0));
        assertEquals(0, extreme.parkNanos(0, 0, 0, 7));
        assertEquals(2_000_000, extreme.halfLifeNanos(0, 0, 0, 7));
    }

    @Test
    void pairwiseTermsHaveIndependentOutputsAndPhrReversesTheirEffect() {
        var model = function(List.of(0.0, 0.0, 0.0, 0.0, 0.5, 0.2, -0.1), List.of(0.0, 0.0, 0.0, 0.0, -0.4, -0.1, 0.2));
        // c=1, p=+/-1 and b=0 in the standardized coordinates isolate c*p.
        assertEquals(Math.round(15_000 * Math.exp(0.5)), model.parkNanos(1, 4, Math.expm1(8), 7));
        assertEquals(Math.round(15_000 * Math.exp(-0.5)), model.parkNanos(1, 0, Math.expm1(8), 7));
        assertEquals(Math.round(1_000_000 * Math.exp(-0.4)), model.halfLifeNanos(1, 4, Math.expm1(8), 7));
        assertEquals(Math.round(15_000 * Math.exp(0.6)), model.parkNanos(1, 4, Math.expm1(16), 7));
        assertEquals(Math.round(1_000_000 * Math.exp(-0.3)), model.halfLifeNanos(1, 4, Math.expm1(16), 7));
        var zero = function(java.util.Collections.nCopies(7, 0.0), java.util.Collections.nCopies(7, 0.0));
        assertEquals(15_000, zero.parkNanos(1, 4, 100, 7));
        assertEquals(1_000_000, zero.halfLifeNanos(1, 4, 100, 7));
        assertThrows(
                IllegalArgumentException.class,
                () -> function(java.util.Collections.nCopies(7, 0.0), java.util.Collections.nCopies(4, 0.0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> function(java.util.Collections.nCopies(5, 0.0), java.util.Collections.nCopies(5, 0.0)));
    }

    @Test
    void copiesCoefficientsAndRejectsNonfiniteConfig() {
        var coefficients = new ArrayList<>(List.of(0.0, 0.0, 0.0, 0.0));
        var model = function(coefficients, List.of(0.0, 0.0, 0.0, 0.0));
        coefficients.set(0, 1.0);
        for (var values : List.of(
                model.means(),
                model.scales(),
                model.supportMin(),
                model.supportMax(),
                model.parkCoefficients(),
                model.halfLifeCoefficients())) {
            assertThrows(UnsupportedOperationException.class, () -> values.set(0, 2.0));
        }
        var equalModel = function(List.of(0.0, 0.0, 0.0, 0.0), List.of(0.0, 0.0, 0.0, 0.0));
        assertEquals(equalModel, model);
        assertEquals(equalModel.hashCode(), model.hashCode());
        assertEquals(15_000, model.parkNanos(0, 0, 0, 7));
        assertThrows(
                IllegalArgumentException.class,
                () -> function(List.of(Double.NaN, 0.0, 0.0, 0.0), List.of(0.0, 0.0, 0.0, 0.0)));
    }
}
