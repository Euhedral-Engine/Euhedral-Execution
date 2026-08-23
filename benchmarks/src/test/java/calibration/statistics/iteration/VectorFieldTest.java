package calibration.statistics.iteration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calibration.statistics.DecisionGrid;
import calibration.statistics.VectorCell;
import calibration.statistics.VectorField;
import java.util.List;
import org.junit.jupiter.api.Test;

class VectorFieldTest {

    private static final double EPSILON = 1e-9;

    @Test
    void testKnownDirectionalVector() {
        // Sequence: (0,1) -> (1,2) -> (0,1) -> (1,2) -> (0,1) -> (1,2)
        int state11 = TransitionAnalysis.toState(0, 1);
        int state22 = TransitionAnalysis.toState(1, 2);

        int[] seq = {state11, state22, state11, state22, state11, state22};
        VectorField field = VectorField.compute(seq);

        // Cell (1, 1) has 3 outgoing transitions to (2, 2)
        VectorCell cell11 = field.cell(0, 1);
        assertTrue(cell11.hasVector());
        assertEquals(3L, cell11.transitionCount());
        assertEquals(1.0, cell11.meanDeltaContention(), EPSILON);
        assertEquals(1.0, cell11.meanDeltaBody(), EPSILON);
        assertEquals(Math.hypot(1.0, 1.0), cell11.magnitude(), EPSILON);

        // Cell (2, 2) has 2 outgoing transitions to (1, 1)
        VectorCell cell22 = field.cell(1, 2);
        assertTrue(cell22.hasVector());
        assertEquals(2L, cell22.transitionCount());
        assertEquals(-1.0, cell22.meanDeltaContention(), EPSILON);
        assertEquals(-1.0, cell22.meanDeltaBody(), EPSILON);
        assertEquals(Math.hypot(-1.0, -1.0), cell22.magnitude(), EPSILON);

        // Cell (0, 0) has no outgoing transitions
        VectorCell cell00 = field.cell(0, 0);
        assertFalse(cell00.hasVector());
        assertEquals(0L, cell00.transitionCount());
        assertTrue(Double.isNaN(cell00.meanDeltaContention()));
        assertTrue(Double.isNaN(cell00.meanDeltaBody()));
        assertTrue(Double.isNaN(cell00.magnitude()));
    }

    @Test
    void testAveragedDisplacements() {
        // From (0, 1):
        // 1 transition to (0, 3) -> delta = (0, 2)
        // 1 transition to (1, 1) -> delta = (1, 0)
        int state11 = TransitionAnalysis.toState(0, 1);
        int state13 = TransitionAnalysis.toState(0, 3);
        int state31 = TransitionAnalysis.toState(1, 1);

        long[][] counts = new long[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES];
        counts[state11][state13] = 1L;
        counts[state11][state31] = 1L;

        VectorField field = VectorField.compute(counts);
        VectorCell cell11 = field.cell(state11);

        assertTrue(cell11.hasVector());
        assertEquals(2L, cell11.transitionCount());
        assertEquals(0.5, cell11.meanDeltaContention(), EPSILON);
        assertEquals(1.0, cell11.meanDeltaBody(), EPSILON);
        assertEquals(Math.hypot(0.5, 1.0), cell11.magnitude(), EPSILON);
    }

    @Test
    void testComputeWithList() {
        int s6 = TransitionAnalysis.toState(0, 1);
        int s12 = TransitionAnalysis.toState(1, 2);

        VectorField field = VectorField.compute(List.of(s6, s12));
        VectorCell cell = field.cell(0, 1);
        assertTrue(cell.hasVector());
        assertEquals(1.0, cell.meanDeltaContention(), EPSILON);
        assertEquals(1.0, cell.meanDeltaBody(), EPSILON);
    }
}
