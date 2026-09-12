package calibration.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import org.junit.jupiter.api.Test;

class QuantileSummaryTest {

    private static final double EPSILON = 1e-9;

    @Test
    void testEmptyInput() {
        QuantileSummary summary = QuantileSummary.of();
        assertTrue(Double.isNaN(summary.p25()));
        assertTrue(Double.isNaN(summary.p50()));
        assertTrue(Double.isNaN(summary.p75()));
        assertTrue(Double.isNaN(summary.p95()));
        assertTrue(Double.isNaN(summary.iqr()));
        assertTrue(Double.isNaN(summary.normalizedIqr()));
        assertTrue(Double.isNaN(summary.p95ToP50Ratio()));

        QuantileSummary empty = QuantileSummary.empty();
        assertEquals(summary, empty);

        QuantileSummary fromEmptyList = QuantileSummary.of(List.of());
        assertEquals(summary, fromEmptyList);
    }

    @Test
    void testQuantilesAgainstCommonsMathPercentile() {
        double[] values = {10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0};

        Percentile percentile = new Percentile();
        percentile.setData(values);

        QuantileSummary summary = QuantileSummary.of(values);

        double expectedP25 = percentile.evaluate(25.0);
        double expectedP50 = percentile.evaluate(50.0);
        double expectedP75 = percentile.evaluate(75.0);
        double expectedP95 = percentile.evaluate(95.0);
        double expectedIqr = expectedP75 - expectedP25;
        double expectedNormalizedIqr = expectedIqr / expectedP50;
        double expectedRatio = expectedP95 / expectedP50;

        assertEquals(expectedP25, summary.p25(), EPSILON);
        assertEquals(expectedP50, summary.p50(), EPSILON);
        assertEquals(expectedP75, summary.p75(), EPSILON);
        assertEquals(expectedP95, summary.p95(), EPSILON);
        assertEquals(expectedIqr, summary.iqr(), EPSILON);
        assertEquals(expectedNormalizedIqr, summary.normalizedIqr(), EPSILON);
        assertEquals(expectedRatio, summary.p95ToP50Ratio(), EPSILON);
    }

    @Test
    void testZeroP50HandledGracefully() {
        double[] values = {-10.0, 0.0, 10.0};
        QuantileSummary summary = QuantileSummary.of(values);

        assertEquals(0.0, summary.p50(), EPSILON);
        assertTrue(Double.isNaN(summary.normalizedIqr()));
        assertTrue(Double.isNaN(summary.p95ToP50Ratio()));
    }

    @Test
    void testNonFiniteRejection() {
        assertThrows(IllegalArgumentException.class, () -> QuantileSummary.of(1.0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> QuantileSummary.of(1.0, Double.POSITIVE_INFINITY));
    }
}
