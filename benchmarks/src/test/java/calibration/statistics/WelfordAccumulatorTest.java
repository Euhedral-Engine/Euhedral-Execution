package calibration.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.math3.stat.descriptive.moment.StandardDeviation;
import org.apache.commons.math3.stat.descriptive.moment.Variance;
import org.junit.jupiter.api.Test;

class WelfordAccumulatorTest {

    private static final double EPSILON = 1e-9;

    @Test
    void testInitialEmptyState() {
        WelfordAccumulator accumulator = new WelfordAccumulator();
        assertEquals(0L, accumulator.count());
        assertTrue(Double.isNaN(accumulator.mean()));
        assertTrue(Double.isNaN(accumulator.m2()));
        assertTrue(Double.isNaN(accumulator.sampleVariance()));
        assertTrue(Double.isNaN(accumulator.standardDeviation()));
    }

    @Test
    void testFirstSampleBehavior() {
        WelfordAccumulator accumulator = new WelfordAccumulator();
        accumulator.record(10.0);

        assertEquals(1L, accumulator.count());
        assertEquals(10.0, accumulator.mean(), EPSILON);
        assertEquals(0.0, accumulator.m2(), EPSILON);
        assertTrue(Double.isNaN(accumulator.sampleVariance()));
        assertTrue(Double.isNaN(accumulator.standardDeviation()));
    }

    @Test
    void testTwoSamples() {
        WelfordAccumulator accumulator = new WelfordAccumulator();
        accumulator.record(10.0);
        accumulator.record(20.0);

        assertEquals(2L, accumulator.count());
        assertEquals(15.0, accumulator.mean(), EPSILON);
        assertEquals(50.0, accumulator.m2(), EPSILON);
        assertEquals(50.0, accumulator.sampleVariance(), EPSILON);
        assertEquals(Math.sqrt(50.0), accumulator.standardDeviation(), EPSILON);
    }

    @Test
    void testMatchesCommonsMathVarianceOverKnownSequences() {
        double[] values = {12.5, 45.2, 33.1, 98.7, 54.3, 11.2, 76.4, 23.8, 89.1, 67.5};

        WelfordAccumulator accumulator = new WelfordAccumulator();
        accumulator.recordAll(values);

        Variance commonsVariance = new Variance();
        double expectedVariance = commonsVariance.evaluate(values);

        StandardDeviation commonsStdDev = new StandardDeviation();
        double expectedStdDev = commonsStdDev.evaluate(values);

        assertEquals(values.length, accumulator.count());
        assertEquals(expectedVariance, accumulator.sampleVariance(), EPSILON);
        assertEquals(expectedStdDev, accumulator.standardDeviation(), EPSILON);
    }

    @Test
    void testDeterministicReset() {
        WelfordAccumulator accumulator = new WelfordAccumulator();
        accumulator.record(100.0);
        accumulator.record(200.0);
        accumulator.record(300.0);

        accumulator.reset();
        assertEquals(0L, accumulator.count());
        assertTrue(Double.isNaN(accumulator.mean()));
        assertTrue(Double.isNaN(accumulator.m2()));
        assertTrue(Double.isNaN(accumulator.sampleVariance()));

        accumulator.record(5.0);
        accumulator.record(15.0);
        assertEquals(2L, accumulator.count());
        assertEquals(10.0, accumulator.mean(), EPSILON);
        assertEquals(50.0, accumulator.sampleVariance(), EPSILON);
    }

    @Test
    void testRejectNonFiniteInputs() {
        WelfordAccumulator accumulator = new WelfordAccumulator();
        accumulator.record(10.0);
        accumulator.record(20.0);

        assertThrows(IllegalArgumentException.class, () -> accumulator.record(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> accumulator.record(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> accumulator.record(Double.NEGATIVE_INFINITY));

        // State remains intact after rejection
        assertEquals(2L, accumulator.count());
        assertEquals(15.0, accumulator.mean(), EPSILON);
        assertEquals(50.0, accumulator.sampleVariance(), EPSILON);
    }
}
