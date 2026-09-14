package io.euhedral_execution.benchmarks.cfd.validation;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.GridShape;
import org.junit.jupiter.api.Test;

class ReferenceSamplingTest {
    @Test
    void trilinearSamplingPreservesAnAffineVectorField() {
        var candidate = new Snapshot(new GridShape(2, 2, 2), 1, 1);
        var reference = new Snapshot(new GridShape(4, 4, 4), .5, 1);
        affine(candidate);
        affine(reference);
        var sampled = ReferenceSampling.atCandidateCenters(candidate, reference);
        assertEquals(1, sampled.coverage());
        assertTrue(FieldComparison.compare(
                        sampled.candidate(),
                        sampled.reference(),
                        ValidationSuite.Gauge.ABSOLUTE,
                        FieldComparisonTest.LIMITS)
                .passed());
    }

    @Test
    void missingFluidCornersExcludeSamplesAndReportCoverage() {
        var candidate = new Snapshot(new GridShape(2, 2, 2), 1, 1);
        var reference = new Snapshot(new GridShape(4, 4, 4), .5, 1);
        affine(candidate);
        affine(reference);
        reference.ids[0] = 1;
        var sampled = ReferenceSampling.atCandidateCenters(candidate, reference);
        assertEquals(8, sampled.requestedFluid());
        assertEquals(7, sampled.coveredFluid());
        assertEquals(.875, sampled.coverage());
    }

    @Test
    void coordinatesOutsideReferenceCentersAreNeverExtrapolated() {
        var candidate = new Snapshot(new GridShape(4, 4, 4), .5, 1);
        var reference = new Snapshot(new GridShape(2, 2, 2), 1, 1);
        affine(candidate);
        affine(reference);
        assertEquals(
                8, ReferenceSampling.atCandidateCenters(candidate, reference).coveredFluid());
    }

    private static void affine(Snapshot s) {
        for (int z = 0; z < s.shape.nz(); z++)
            for (int y = 0; y < s.shape.ny(); y++)
                for (int x = 0; x < s.shape.nx(); x++) {
                    int i = x + s.shape.nx() * (y + s.shape.ny() * z);
                    s.fields[0][i] = (x + .5) * s.spacing;
                    s.fields[1][i] = (y + .5) * s.spacing;
                    s.fields[2][i] = (z + .5) * s.spacing;
                    s.fields[3][i] = 1;
                    s.fields[4][i] = s.fields[0][i] + s.fields[1][i];
                }
    }
}
