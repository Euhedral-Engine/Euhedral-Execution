package calibration.statistics.iteration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ScalarSummaryTest {

    private static final double EPSILON = 1e-6;

    @Test
    void testEmptyScalarSummary() {
        ScalarSummary empty = ScalarSummary.empty();
        assertTrue(empty.isEmpty());
        assertEquals(0L, empty.count());
        assertTrue(Double.isNaN(empty.mean()));
        assertTrue(Double.isNaN(empty.variance()));
        assertTrue(Double.isNaN(empty.standardDeviation()));
        assertTrue(Double.isNaN(empty.coefficientOfVariation()));
        assertTrue(Double.isNaN(empty.min()));
        assertTrue(Double.isNaN(empty.max()));
        assertTrue(Double.isNaN(empty.median()));
        assertTrue(Double.isNaN(empty.p25()));
        assertTrue(Double.isNaN(empty.p50()));
        assertTrue(Double.isNaN(empty.p75()));
        assertTrue(Double.isNaN(empty.p95()));
        assertTrue(Double.isNaN(empty.iqr()));
        assertTrue(Double.isNaN(empty.normalizedIqr()));
        assertTrue(Double.isNaN(empty.p95ToP50Ratio()));
    }

    @Test
    void testSingleElementSummary() {
        ScalarSummary summary = ScalarSummary.of(42.0);
        assertFalse(summary.isEmpty());
        assertEquals(1L, summary.count());
        assertEquals(42.0, summary.mean(), EPSILON);
        assertTrue(Double.isNaN(summary.variance()));
        assertTrue(Double.isNaN(summary.standardDeviation()));
        assertEquals(42.0, summary.min(), EPSILON);
        assertEquals(42.0, summary.max(), EPSILON);
        assertEquals(42.0, summary.median(), EPSILON);
        assertEquals(42.0, summary.p50(), EPSILON);
    }

    @Test
    void testLongArrayFactory() {
        long[] values = {10L, 20L, 30L, 40L, 50L};
        ScalarSummary summary = ScalarSummary.of(values);

        assertEquals(5L, summary.count());
        assertEquals(30.0, summary.mean(), EPSILON);
        assertEquals(250.0, summary.variance(), EPSILON);
        assertEquals(Math.sqrt(250.0), summary.standardDeviation(), EPSILON);
        assertEquals(10.0, summary.min(), EPSILON);
        assertEquals(50.0, summary.max(), EPSILON);
        assertEquals(30.0, summary.median(), EPSILON);
    }
}
