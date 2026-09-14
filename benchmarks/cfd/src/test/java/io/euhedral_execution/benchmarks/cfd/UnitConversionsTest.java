package io.euhedral_execution.benchmarks.cfd;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.*;
import io.euhedral_execution.benchmarks.cfd.config.SimulationConfig.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class UnitConversionsTest {
    @Test
    void allPhysicalAndLatticeConversionsRoundTripIncludingSignedValues() {
        var u = UnitConversions.of(0.01, 0.001, 500);
        assertEquals(10, u.velocityScale());
        assertEquals(10000, u.accelerationScale());
        assertEquals(0.1, u.viscosityScale());
        assertEquals(50000, u.pressureScale());
        assertEquals(5, u.forceScale());
        for (double value : new double[] {0, 0.01, -0.03, 27}) {
            assertEquals(value, u.lengthToPhysical(u.lengthToLattice(value)), 1e-14);
            assertEquals(value, u.timeToPhysical(u.timeToLattice(value)), 1e-14);
            assertEquals(value, u.densityToPhysical(u.densityToLattice(value)), 1e-14);
            assertEquals(value, u.velocityToPhysical(u.velocityToLattice(value)), 1e-14);
            assertEquals(value, u.accelerationToPhysical(u.accelerationToLattice(value)), 1e-14);
            assertEquals(value, u.viscosityToPhysical(u.viscosityToLattice(value)), 1e-14);
            assertEquals(value, u.pressureToPhysical(u.pressureToLattice(value)), 1e-14);
            assertEquals(value, u.forceToPhysical(u.forceToLattice(value)), 1e-14);
        }
    }

    @Test
    void diffusiveRefinementPreservesReynoldsGeometryAndDuration() {
        var coarse = physical(0.01, 0.001, new GridShape(10, 10, 10));
        var fine = physical(0.005, 0.00025, new GridShape(20, 20, 20));
        assertEquals(coarse.physics().reynolds(), fine.physics().reynolds(), 1e-14);
        assertEquals(coarse.physics().tau(), fine.physics().tau(), 1e-15);
        assertEquals(coarse.physics().initialMach() / 2, fine.physics().initialMach(), 1e-15);
        assertEquals(
                coarse.physics().acceleration().x() / 8,
                fine.physics().acceleration().x(),
                1e-15);
        assertEquals(coarse.steps() * 4, fine.steps());
        assertEquals(coarse.physicalDurationSeconds(), fine.physicalDurationSeconds());
        assertEquals(
                coarse.config().grid().ny() * coarse.physics().voxelWidth(),
                fine.config().grid().ny() * fine.physics().voxelWidth(),
                0);
    }

    private static CfdConfiguration physical(double dx, double dt, GridShape shape) {
        return ConfigLoader.resolve(
                Path.of("units.json"),
                new SimulationConfig(
                        1,
                        shape,
                        new Physics(
                                1.0,
                                null,
                                new Physical(dx, dt, 1000, 0.01, new Vector3(0.1, 0, 0), new Vector3(0.2, 0, 0)),
                                0.1),
                        null,
                        new Execution(null, 0.1, null, null),
                        null,
                        null),
                100_000_000);
    }

    @Test
    void invalidScalesOverflowAndNonzeroUnderflowAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> UnitConversions.of(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> UnitConversions.of(1, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> UnitConversions.of(1, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> UnitConversions.of(1e200, 1e-200, 1));
        assertThrows(IllegalArgumentException.class, () -> UnitConversions.of(1e-200, 1e200, 1));
        var u = UnitConversions.of(0.01, 0.001, 500);
        assertThrows(IllegalArgumentException.class, () -> u.forceToPhysical(Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> u.accelerationToLattice(Double.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> u.velocityToLattice(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new UnitConversions(1, 1, 1, 2, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Guards(0.0, null));
        assertThrows(IllegalArgumentException.class, () -> new Guards(null, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Execution(1L, null, null, null, -1L));
    }
}
