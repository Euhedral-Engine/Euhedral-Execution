package calibration.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.junit.jupiter.api.Test;

class DescriptiveSummaryTest {

    private static final double EPSILON = 1e-9;

    @Test
    void testEmptyInput() {
        DescriptiveSummary summary = DescriptiveSummary.of();
        assertEquals(0L, summary.count());
        assertTrue(Double.isNaN(summary.mean()));
        assertTrue(Double.isNaN(summary.variance()));
        assertTrue(Double.isNaN(summary.standardDeviation()));
        assertTrue(Double.isNaN(summary.coefficientOfVariation()));
        assertTrue(Double.isNaN(summary.min()));
        assertTrue(Double.isNaN(summary.max()));
        assertTrue(Double.isNaN(summary.median()));

        DescriptiveSummary emptySummary = DescriptiveSummary.empty();
        assertEquals(summary, emptySummary);

        DescriptiveSummary fromEmptyList = DescriptiveSummary.of(List.of());
        assertEquals(summary, fromEmptyList);
    }

    @Test
    void testSingleValueInput() {
        DescriptiveSummary summary = DescriptiveSummary.of(42.0);
        assertEquals(1L, summary.count());
        assertEquals(42.0, summary.mean(), EPSILON);
        assertTrue(Double.isNaN(summary.variance()));
        assertTrue(Double.isNaN(summary.standardDeviation()));
        assertTrue(Double.isNaN(summary.coefficientOfVariation()));
        assertEquals(42.0, summary.min(), EPSILON);
        assertEquals(42.0, summary.max(), EPSILON);
        assertEquals(42.0, summary.median(), EPSILON);
    }

    @Test
    void testKnownSequenceAgainstCommonsMath() {
        double[] values = {2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0};

        DescriptiveStatistics commonsStats = new DescriptiveStatistics();
        for (double v : values) {
            commonsStats.addValue(v);
        }

        DescriptiveSummary summary = DescriptiveSummary.of(values);

        assertEquals(8L, summary.count());
        assertEquals(5.0, summary.mean(), EPSILON);
        assertEquals(commonsStats.getMean(), summary.mean(), EPSILON);
        assertEquals(commonsStats.getVariance(), summary.variance(), EPSILON);
        assertEquals(32.0 / 7.0, summary.variance(), EPSILON);
        assertEquals(commonsStats.getStandardDeviation(), summary.standardDeviation(), EPSILON);
        assertEquals(Math.sqrt(32.0 / 7.0), summary.standardDeviation(), EPSILON);
        assertEquals(summary.standardDeviation() / summary.mean(), summary.coefficientOfVariation(), EPSILON);
        assertEquals(2.0, summary.min(), EPSILON);
        assertEquals(9.0, summary.max(), EPSILON);
        assertEquals(commonsStats.getPercentile(50.0), summary.median(), EPSILON);
    }

    @Test
    void testZeroMeanCoefficientOfVariation() {
        double[] values = {-2.0, 2.0};
        DescriptiveSummary summary = DescriptiveSummary.of(values);

        assertEquals(2L, summary.count());
        assertEquals(0.0, summary.mean(), EPSILON);
        assertTrue(Double.isNaN(summary.coefficientOfVariation()));
    }

    @Test
    void testNonFiniteRejection() {
        assertThrows(IllegalArgumentException.class, () -> DescriptiveSummary.of(1.0, Double.NaN, 2.0));
        assertThrows(IllegalArgumentException.class, () -> DescriptiveSummary.of(1.0, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> DescriptiveSummary.of(Double.NEGATIVE_INFINITY, 2.0));
    }

    @Test
    void testRelativeThroughputChange() {
        assertEquals(25.0, DescriptiveSummary.relativeThroughputChange(100.0, 125.0), EPSILON);
        assertEquals(-20.0, DescriptiveSummary.relativeThroughputChange(100.0, 80.0), EPSILON);
        assertEquals(0.0, DescriptiveSummary.relativeThroughputChange(50.0, 50.0), EPSILON);
        assertTrue(Double.isNaN(DescriptiveSummary.relativeThroughputChange(0.0, 10.0)));
        assertTrue(Double.isNaN(DescriptiveSummary.relativeThroughputChange(Double.NaN, 10.0)));
        assertTrue(Double.isNaN(DescriptiveSummary.relativeThroughputChange(10.0, Double.NaN)));
    }
}
