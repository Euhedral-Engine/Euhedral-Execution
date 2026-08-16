package calibration.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SampleComparatorTest {

    private static final double EPSILON = 1e-9;

    @Test
    void testClearAWinner() {
        // A has much higher throughput than B
        double meanA = 100.0;
        double varianceA = 1.0;
        long countA = 100L;

        double meanB = 50.0;
        double varianceB = 1.0;
        long countB = 100L;

        SampleComparison comparison = SampleComparator.compare(meanA, varianceA, countA, meanB, varianceB, countB);

        assertEquals(ComparisonOutcome.A_BETTER, comparison.outcome());
        assertEquals(-50.0, comparison.delta(), EPSILON);
        assertEquals(2.0 * Math.sqrt(0.01 + 0.01), comparison.uncertainty(), EPSILON);
        assertEquals(0.01 * 100.0, comparison.practical(), EPSILON);
        assertEquals(Math.max(comparison.uncertainty(), comparison.practical()), comparison.margin(), EPSILON);
    }

    @Test
    void testClearBWinner() {
        // B has much higher throughput than A
        double meanA = 50.0;
        double varianceA = 1.0;
        long countA = 100L;

        double meanB = 100.0;
        double varianceB = 1.0;
        long countB = 100L;

        SampleComparison comparison = SampleComparator.compare(meanA, varianceA, countA, meanB, varianceB, countB);

        assertEquals(ComparisonOutcome.B_BETTER, comparison.outcome());
        assertEquals(50.0, comparison.delta(), EPSILON);
    }

    @Test
    void testEquivalentZeroVarianceSamples() {
        double meanA = 100.0;
        double varianceA = 0.0;
        long countA = 10L;

        double meanB = 100.0;
        double varianceB = 0.0;
        long countB = 10L;

        SampleComparison comparison = SampleComparator.compare(meanA, varianceA, countA, meanB, varianceB, countB);

        assertEquals(ComparisonOutcome.EQUIVALENT, comparison.outcome());
        assertEquals(0.0, comparison.delta(), EPSILON);
        assertEquals(0.0, comparison.uncertainty(), EPSILON);
        assertEquals(1.0, comparison.practical(), EPSILON);
        assertEquals(1.0, comparison.margin(), EPSILON);
    }

    @Test
    void testEquivalentLowNoiseSamples() {
        double meanA = 100.0;
        double varianceA = 0.01;
        long countA = 100L;

        double meanB = 100.5;
        double varianceB = 0.01;
        long countB = 100L;

        SampleComparison comparison = SampleComparator.compare(meanA, varianceA, countA, meanB, varianceB, countB);

        assertEquals(ComparisonOutcome.EQUIVALENT, comparison.outcome());
        assertEquals(0.5, comparison.delta(), EPSILON);
        assertTrue(comparison.uncertainty() <= comparison.practical());
        assertTrue(Math.abs(comparison.delta()) <= comparison.practical());
    }

    @Test
    void testOverlappingNoisySamplesInconclusive() {
        // Difference is within noise margin, but uncertainty exceeds practical margin
        double meanA = 100.0;
        double varianceA = 1000.0;
        long countA = 10L;

        double meanB = 105.0;
        double varianceB = 1000.0;
        long countB = 10L;

        SampleComparison comparison = SampleComparator.compare(meanA, varianceA, countA, meanB, varianceB, countB);

        assertEquals(ComparisonOutcome.INCONCLUSIVE, comparison.outcome());
        assertEquals(5.0, comparison.delta(), EPSILON);
        assertTrue(comparison.uncertainty() > comparison.practical());
    }

    @Test
    void testInsufficientSampleCount() {
        SampleComparison singleSampleA = SampleComparator.compare(100.0, 1.0, 1L, 100.0, 1.0, 10L);
        assertEquals(ComparisonOutcome.INCONCLUSIVE, singleSampleA.outcome());
        assertTrue(Double.isNaN(singleSampleA.uncertainty()));

        SampleComparison singleSampleB = SampleComparator.compare(100.0, 1.0, 10L, 100.0, 1.0, 1L);
        assertEquals(ComparisonOutcome.INCONCLUSIVE, singleSampleB.outcome());
        assertTrue(Double.isNaN(singleSampleB.uncertainty()));

        SampleComparison zeroCount = SampleComparator.compare(100.0, 1.0, 0L, 100.0, 1.0, 0L);
        assertEquals(ComparisonOutcome.INCONCLUSIVE, zeroCount.outcome());
    }

    @Test
    void testComparisonWithWelfordAccumulators() {
        WelfordAccumulator accA = new WelfordAccumulator();
        accA.recordAll(99.0, 100.0, 101.0);

        WelfordAccumulator accB = new WelfordAccumulator();
        accB.recordAll(199.0, 200.0, 201.0);

        SampleComparison comparison = SampleComparator.compare(accA, accB);
        assertEquals(ComparisonOutcome.B_BETTER, comparison.outcome());
        assertEquals(100.0, comparison.delta(), EPSILON);
    }
}
