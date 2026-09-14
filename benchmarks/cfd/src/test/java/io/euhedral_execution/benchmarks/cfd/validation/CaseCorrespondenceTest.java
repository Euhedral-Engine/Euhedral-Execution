package io.euhedral_execution.benchmarks.cfd.validation;

import static org.junit.jupiter.api.Assertions.*;

import io.euhedral_execution.benchmarks.cfd.config.ConfigLoader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaseCorrespondenceTest {
    @Test
    void diffusiveGridRefinementPreservesChannelPhysics() throws Exception {
        var a = ConfigLoader.load(Path.of("validation/cases/channel.json"));
        var b = ConfigLoader.load(Path.of("validation/cases/channel-fine.json"));
        assertNull(CaseCorrespondence.difference(a, b));
        assertEquals(40, ValidationRunner.referenceStep(10, a, b));
    }

    @Test
    void viscosityAndForceSignChangesAreNotTheSamePhysicalProblem() throws Exception {
        Path path = Path.of("validation/cases/channel.json");
        var a = ConfigLoader.load(path);
        assertEquals(
                "physical viscosity",
                CaseCorrespondence.difference(a, ConfigLoader.load(path, List.of("physics.lattice.viscosity=0.13"))));
        assertEquals(
                "physical acceleration",
                CaseCorrespondence.difference(
                        a, ConfigLoader.load(path, List.of("physics.lattice.acceleration.x=-0.00001"))));
    }

    @Test
    void tinyPhysicalTimesCannotAliasTimeZero() {
        assertFalse(Snapshot.sameTime(0, 1e-15));
        assertTrue(Snapshot.sameTime(.1 + .2, .3));
    }
}
