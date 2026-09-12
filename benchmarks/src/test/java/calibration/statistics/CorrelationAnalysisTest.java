package calibration.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CorrelationAnalysisTest {

    private static final double EPSILON = 1e-9;

    @Test
    void testPerfectLinearPositiveCorrelation() {
        double[] x = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] y = {2.0, 4.0, 6.0, 8.0, 10.0};

        assertEquals(1.0, CorrelationAnalysis.pearson(x, y), EPSILON);
        assertEquals(1.0, CorrelationAnalysis.spearman(x, y), EPSILON);
    }

    @Test
    void testPerfectLinearNegativeCorrelation() {
        double[] x = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] y = {10.0, 8.0, 6.0, 4.0, 2.0};

        assertEquals(-1.0, CorrelationAnalysis.pearson(x, y), EPSILON);
        assertEquals(-1.0, CorrelationAnalysis.spearman(x, y), EPSILON);
    }

    @Test
    void testMonotonicNonLinearRankCorrelation() {
        double[] x = {1.0, 2.0, 3.0, 4.0, 5.0};
        double[] y = {1.0, 8.0, 27.0, 64.0, 125.0}; // y = x^3

        // Spearman rank correlation is exactly 1.0 because rank order is identical
        assertEquals(1.0, CorrelationAnalysis.spearman(x, y), EPSILON);

        // Pearson linear correlation is strictly less than 1.0
        double pearson = CorrelationAnalysis.pearson(x, y);
        assertTrue(pearson > 0.9 && pearson < 1.0);
    }

    @Test
    void testCorrelationMatrices() {
        // 5 observations of 3 variables:
        // var 0 = x
        // var 1 = 2 * x (perfect positive)
        // var 2 = 10 - 2 * x (perfect negative)
        double[][] data = {
            {1.0, 2.0, 8.0},
            {2.0, 4.0, 6.0},
            {3.0, 6.0, 4.0},
            {4.0, 8.0, 2.0},
            {5.0, 10.0, 0.0}
        };

        double[][] pearsonMatrix = CorrelationAnalysis.pearsonMatrix(data);
        assertEquals(3, pearsonMatrix.length);
        assertEquals(3, pearsonMatrix[0].length);

        assertEquals(1.0, pearsonMatrix[0][0], EPSILON);
        assertEquals(1.0, pearsonMatrix[0][1], EPSILON);
        assertEquals(-1.0, pearsonMatrix[0][2], EPSILON);
        assertEquals(1.0, pearsonMatrix[1][0], EPSILON);
        assertEquals(1.0, pearsonMatrix[1][1], EPSILON);
        assertEquals(-1.0, pearsonMatrix[1][2], EPSILON);
        assertEquals(-1.0, pearsonMatrix[2][0], EPSILON);
        assertEquals(-1.0, pearsonMatrix[2][1], EPSILON);
        assertEquals(1.0, pearsonMatrix[2][2], EPSILON);

        double[][] spearmanMatrix = CorrelationAnalysis.spearmanMatrix(data);
        assertEquals(1.0, spearmanMatrix[0][1], EPSILON);
        assertEquals(-1.0, spearmanMatrix[0][2], EPSILON);
    }

    @Test
    void testValidation() {
        assertThrows(NullPointerException.class, () -> CorrelationAnalysis.pearson(null, new double[] {1.0, 2.0}));
        assertThrows(NullPointerException.class, () -> CorrelationAnalysis.pearson(new double[] {1.0, 2.0}, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> CorrelationAnalysis.pearson(new double[] {1.0}, new double[] {1.0}));
        assertThrows(
                IllegalArgumentException.class,
                () -> CorrelationAnalysis.pearson(new double[] {1.0, 2.0}, new double[] {1.0, 2.0, 3.0}));
        assertThrows(
                IllegalArgumentException.class,
                () -> CorrelationAnalysis.pearson(new double[] {1.0, Double.NaN}, new double[] {1.0, 2.0}));

        assertThrows(NullPointerException.class, () -> CorrelationAnalysis.pearsonMatrix(null));
        assertThrows(
                IllegalArgumentException.class, () -> CorrelationAnalysis.pearsonMatrix(new double[][] {{1.0, 2.0}}));
        assertThrows(
                IllegalArgumentException.class, () -> CorrelationAnalysis.pearsonMatrix(new double[][] {{1.0}, {2.0}}));
    }
}
