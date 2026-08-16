package calibration.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        OccupancySummary directEmpty = OccupancyMesh.analyze(new long[5][5]);
        assertEquals(summary, directEmpty);
    }

    @Test
    void testSingleCellOccupancy() {
        // Cell at contention M (2), body H (3)
        OccupancyMesh mesh = new OccupancyMesh();
        mesh.record(Band.M, Band.H, 50L);

        OccupancySummary summary = mesh.summarize();

        assertEquals(50L, summary.totalCount());
        assertFalse(summary.isEmpty());
        assertEquals(2.0, summary.contentionCentroid(), EPSILON);
        assertEquals(3.0, summary.bodyCentroid(), EPSILON);
        assertEquals(0.0, summary.contentionVariance(), EPSILON);
        assertEquals(0.0, summary.bodyVariance(), EPSILON);
        assertEquals(0.0, summary.contentionBodyCovariance(), EPSILON);
        assertEquals(0.0, summary.radiusSquared(), EPSILON);
        assertEquals(0.0, summary.radius(), EPSILON);
        assertEquals(1.0, summary.probabilities()[2][3], EPSILON);
    }

    @Test
    void testSymmetricOccupancy() {
        // Uniform distribution across all 25 cells
        long[][] counts = new long[5][5];
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                counts[i][j] = 10L;
            }
        }

        OccupancySummary summary = OccupancyMesh.analyze(counts);

        assertEquals(250L, summary.totalCount());
        assertEquals(2.0, summary.contentionCentroid(), EPSILON);
        assertEquals(2.0, summary.bodyCentroid(), EPSILON);
        // Variance for uniform 0..4 = ((0-2)^2 + (1-2)^2 + (2-2)^2 + (3-2)^2 + (4-2)^2) / 5 = (4+1+0+1+4)/5 = 2.0
        assertEquals(2.0, summary.contentionVariance(), EPSILON);
        assertEquals(2.0, summary.bodyVariance(), EPSILON);
        assertEquals(0.0, summary.contentionBodyCovariance(), EPSILON);
        assertEquals(4.0, summary.radiusSquared(), EPSILON);
        assertEquals(2.0, summary.radius(), EPSILON);
    }

    @Test
    void testDiagonalOccupancyPositiveCovariance() {
        // (0,0), (1,1), (2,2), (3,3), (4,4)
        long[][] counts = new long[5][5];
        for (int i = 0; i < 5; i++) {
            counts[i][i] = 1L;
        }

        OccupancySummary summary = OccupancyMesh.analyze(counts);

        assertEquals(5L, summary.totalCount());
        assertEquals(2.0, summary.contentionCentroid(), EPSILON);
        assertEquals(2.0, summary.bodyCentroid(), EPSILON);
        assertEquals(2.0, summary.contentionVariance(), EPSILON);
        assertEquals(2.0, summary.bodyVariance(), EPSILON);
        // Covariance on diagonal: sum((i-2)*(i-2))/5 = 2.0 > 0
        assertEquals(2.0, summary.contentionBodyCovariance(), EPSILON);
        assertTrue(summary.contentionBodyCovariance() > 0.0);
    }

    @Test
    void testAntiDiagonalOccupancyNegativeCovariance() {
        // (0,4), (1,3), (2,2), (3,1), (4,0)
        long[][] counts = new long[5][5];
        for (int i = 0; i < 5; i++) {
            counts[i][4 - i] = 1L;
        }

        OccupancySummary summary = OccupancyMesh.analyze(counts);

        assertEquals(5L, summary.totalCount());
        assertEquals(2.0, summary.contentionCentroid(), EPSILON);
        assertEquals(2.0, summary.bodyCentroid(), EPSILON);
        assertEquals(2.0, summary.contentionVariance(), EPSILON);
        assertEquals(2.0, summary.bodyVariance(), EPSILON);
        // Covariance on anti-diagonal: sum((i-2)*(2-i))/5 = -2.0 < 0
        assertEquals(-2.0, summary.contentionBodyCovariance(), EPSILON);
        assertTrue(summary.contentionBodyCovariance() < 0.0);
    }

    @Test
    void testCentroidDistance() {
        long[][] countsA = new long[5][5];
        countsA[0][0] = 1L; // Centroid at (0, 0)
        OccupancySummary summaryA = OccupancyMesh.analyze(countsA);

        long[][] countsB = new long[5][5];
        countsB[3][4] = 1L; // Centroid at (3, 4)
        OccupancySummary summaryB = OccupancyMesh.analyze(countsB);

        assertEquals(5.0, summaryA.distanceTo(summaryB), EPSILON);
        assertEquals(5.0, OccupancySummary.distance(summaryA, summaryB), EPSILON);
        assertEquals(5.0, OccupancySummary.distance(0.0, 0.0, 3.0, 4.0), EPSILON);

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
        assertThrows(IllegalArgumentException.class, () -> OccupancyMesh.analyze(new long[4][5]));
        assertThrows(IllegalArgumentException.class, () -> OccupancyMesh.analyze(new long[5][4]));

        long[][] negativeCounts = new long[5][5];
        negativeCounts[1][1] = -5L;
        assertThrows(IllegalArgumentException.class, () -> OccupancyMesh.analyze(negativeCounts));
    }
}
