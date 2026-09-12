package calibration.statistics.iteration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.statistics.Band;
import calibration.statistics.DecisionGrid;
import org.junit.jupiter.api.Test;

class OccupancyMeshTest {

    private static final double EPSILON = 1e-9;

    @Test
    void testEmptyMesh() {
        OccupancyMesh mesh = new OccupancyMesh();
        OccupancySummary summary = mesh.summarize();

        assertEquals(0L, summary.totalCount());
        assertTrue(summary.isEmpty());
        assertTrue(Double.isNaN(summary.contentionCentroid()));
        assertTrue(Double.isNaN(summary.bodyCentroid()));
        assertTrue(Double.isNaN(summary.contentionVariance()));
        assertTrue(Double.isNaN(summary.bodyVariance()));
        assertTrue(Double.isNaN(summary.contentionBodyCovariance()));
        assertTrue(Double.isNaN(summary.radiusSquared()));
        assertTrue(Double.isNaN(summary.radius()));

        OccupancySummary directEmpty =
                OccupancyMesh.analyze(new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES]);
        assertEquals(summary, directEmpty);
    }

    @Test
    void testSingleCellOccupancy() {
        // Cell at high contention (1), body H (3)
        OccupancyMesh mesh = new OccupancyMesh();
        mesh.record(1, Band.H.index(), 50L);

        OccupancySummary summary = mesh.summarize();

        assertEquals(50L, summary.totalCount());
        assertFalse(summary.isEmpty());
        assertEquals(1.0, summary.contentionCentroid(), EPSILON);
        assertEquals(3.0, summary.bodyCentroid(), EPSILON);
        assertEquals(0.0, summary.contentionVariance(), EPSILON);
        assertEquals(0.0, summary.bodyVariance(), EPSILON);
        assertEquals(0.0, summary.contentionBodyCovariance(), EPSILON);
        assertEquals(0.0, summary.radiusSquared(), EPSILON);
        assertEquals(0.0, summary.radius(), EPSILON);
        assertEquals(1.0, summary.probabilities()[1][3], EPSILON);
    }

    @Test
    void testSymmetricOccupancy() {
        // Uniform distribution across all 10 cells
        long[][] counts = new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        for (int i = 0; i < DecisionGrid.CONTENTION_OUTCOMES; i++) {
            for (int j = 0; j < DecisionGrid.BODY_OUTCOMES; j++) {
                counts[i][j] = 10L;
            }
        }

        OccupancySummary summary = OccupancyMesh.analyze(counts);

        assertEquals(100L, summary.totalCount());
        assertEquals(0.5, summary.contentionCentroid(), EPSILON);
        assertEquals(2.0, summary.bodyCentroid(), EPSILON);
        assertEquals(0.25, summary.contentionVariance(), EPSILON);
        assertEquals(2.0, summary.bodyVariance(), EPSILON);
        assertEquals(0.0, summary.contentionBodyCovariance(), EPSILON);
        assertEquals(2.25, summary.radiusSquared(), EPSILON);
        assertEquals(1.5, summary.radius(), EPSILON);
    }

    @Test
    void testDiagonalOccupancyPositiveCovariance() {
        long[][] counts = new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        counts[0][0] = 1L;
        counts[1][4] = 1L;

        OccupancySummary summary = OccupancyMesh.analyze(counts);

        assertEquals(2L, summary.totalCount());
        assertEquals(0.5, summary.contentionCentroid(), EPSILON);
        assertEquals(2.0, summary.bodyCentroid(), EPSILON);
        assertEquals(0.25, summary.contentionVariance(), EPSILON);
        assertEquals(4.0, summary.bodyVariance(), EPSILON);
        assertEquals(1.0, summary.contentionBodyCovariance(), EPSILON);
        assertTrue(summary.contentionBodyCovariance() > 0.0);
    }

    @Test
    void testAntiDiagonalOccupancyNegativeCovariance() {
        long[][] counts = new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        counts[0][4] = 1L;
        counts[1][0] = 1L;

        OccupancySummary summary = OccupancyMesh.analyze(counts);

        assertEquals(2L, summary.totalCount());
        assertEquals(0.5, summary.contentionCentroid(), EPSILON);
        assertEquals(2.0, summary.bodyCentroid(), EPSILON);
        assertEquals(0.25, summary.contentionVariance(), EPSILON);
        assertEquals(4.0, summary.bodyVariance(), EPSILON);
        assertEquals(-1.0, summary.contentionBodyCovariance(), EPSILON);
        assertTrue(summary.contentionBodyCovariance() < 0.0);
    }

    @Test
    void testCentroidDistance() {
        long[][] countsA = new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        countsA[0][0] = 1L; // Centroid at (0, 0)
        OccupancySummary summaryA = OccupancyMesh.analyze(countsA);

        long[][] countsB = new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        countsB[1][4] = 1L; // Centroid at (1, 4)
        OccupancySummary summaryB = OccupancyMesh.analyze(countsB);

        assertEquals(Math.sqrt(17.0), summaryA.distanceTo(summaryB), EPSILON);
        assertEquals(Math.sqrt(17.0), OccupancySummary.distance(summaryA, summaryB), EPSILON);
        assertEquals(Math.sqrt(17.0), OccupancySummary.distance(0.0, 0.0, 1.0, 4.0), EPSILON);

        assertTrue(Double.isNaN(summaryA.distanceTo(OccupancySummary.EMPTY)));
        assertTrue(Double.isNaN(OccupancySummary.distance(summaryA, null)));
    }

    @Test
    void testBandEnumMapping() {
        assertEquals(0, Band.XS.index());
        assertEquals(1, Band.S.index());
        assertEquals(2, Band.M.index());
        assertEquals(3, Band.H.index());
        assertEquals(4, Band.XH.index());

        assertEquals(Band.XS, Band.fromIndex(0));
        assertEquals(Band.S, Band.fromIndex(1));
        assertEquals(Band.M, Band.fromIndex(2));
        assertEquals(Band.H, Band.fromIndex(3));
        assertEquals(Band.XH, Band.fromIndex(4));

        assertThrows(IllegalArgumentException.class, () -> Band.fromIndex(-1));
        assertThrows(IllegalArgumentException.class, () -> Band.fromIndex(5));
    }

    @Test
    void testValidation() {
        assertThrows(NullPointerException.class, () -> OccupancyMesh.analyze(null));
        assertThrows(IllegalArgumentException.class, () -> OccupancyMesh.analyze(new long[1][5]));
        assertThrows(IllegalArgumentException.class, () -> OccupancyMesh.analyze(new long[2][4]));

        long[][] negativeCounts = new long[DecisionGrid.CONTENTION_OUTCOMES][DecisionGrid.BODY_OUTCOMES];
        negativeCounts[1][1] = -5L;
        assertThrows(IllegalArgumentException.class, () -> OccupancyMesh.analyze(negativeCounts));
    }
}
