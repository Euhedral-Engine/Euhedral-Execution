package calibration.statistics.iteration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calibration.statistics.DecisionGrid;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransitionAnalysisTest {

    private static final double EPSILON = 1e-9;

    @Test
    void testStateConversions() {
        for (int i = 0; i < DecisionGrid.CONTENTION_OUTCOMES; i++) {
            for (int j = 0; j < DecisionGrid.BODY_OUTCOMES; j++) {
                int state = TransitionAnalysis.toState(i, j);
                assertEquals(i * 5 + j, state);
                assertEquals(i, TransitionAnalysis.contentionBandOf(state));
                assertEquals(j, TransitionAnalysis.bodyBandOf(state));
            }
        }

        assertThrows(IllegalArgumentException.class, () -> TransitionAnalysis.toState(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> TransitionAnalysis.toState(0, 5));
        assertThrows(IllegalArgumentException.class, () -> TransitionAnalysis.contentionBandOf(-1));
        assertThrows(IllegalArgumentException.class, () -> TransitionAnalysis.bodyBandOf(DecisionGrid.TOTAL_STATES));
    }

    @Test
    void testEmptyAndShortSequences() {
        TransitionAnalysis analysis = TransitionAnalysis.compute(new int[0]);
        for (int i = 0; i < DecisionGrid.TOTAL_STATES; i++) {
            assertEquals(0.0, analysis.selfTransitionRate(i), EPSILON);
            assertEquals(-1, analysis.dominantOutgoingState(i));
            assertEquals(0.0, analysis.dominantOutgoingProbability(i), EPSILON);
        }

        TransitionAnalysis singleElement = TransitionAnalysis.compute(new int[] {7});
        assertEquals(0.0, singleElement.selfTransitionRate(7), EPSILON);
        assertEquals(-1, singleElement.dominantOutgoingState(7));
    }

    @Test
    void testTransitionNormalization() {
        // Sequence from state 0 to state 1 three times, and state 0 to state 2 once
        // 0 -> 1 -> 0 -> 1 -> 0 -> 1 -> 0 -> 2
        int[] seq = {0, 1, 0, 1, 0, 1, 0, 2};
        TransitionAnalysis analysis = TransitionAnalysis.compute(seq);

        long[][] counts = analysis.transitionCounts();
        double[][] probs = analysis.transitionProbabilities();

        assertEquals(3L, counts[0][1]);
        assertEquals(1L, counts[0][2]);
        assertEquals(3L, counts[1][0]);

        // State 0 outgoing total = 4
        assertEquals(0.75, probs[0][1], EPSILON);
        assertEquals(0.25, probs[0][2], EPSILON);
        assertEquals(1.0, probs[0][1] + probs[0][2], EPSILON);

        // State 1 outgoing total = 3 (all to 0)
        assertEquals(1.0, probs[1][0], EPSILON);

        // State 2 has no outgoing transitions -> row remains all 0.0
        for (int b = 0; b < DecisionGrid.TOTAL_STATES; b++) {
            assertEquals(0.0, probs[2][b], EPSILON);
        }
        assertEquals(-1, analysis.dominantOutgoingState(2));
        assertEquals(0.0, analysis.dominantOutgoingProbability(2), EPSILON);
    }

    @Test
    void testSelfTransitionBehavior() {
        // 4 -> 4 -> 4 -> 4
        int[] seq = {4, 4, 4, 4};
        TransitionAnalysis analysis = TransitionAnalysis.compute(seq);

        assertEquals(3L, analysis.transitionCounts()[4][4]);
        assertEquals(1.0, analysis.selfTransitionRate(4), EPSILON);
        assertEquals(4, analysis.dominantOutgoingState(4));
        assertEquals(1.0, analysis.dominantOutgoingProbability(4), EPSILON);
    }

    @Test
    void testTwoCellOscillationPure() {
        // Sequence 0 -> 1 -> 0 -> 1 -> 0 -> 1 (5 transitions, all between 0 and 1)
        int[] seq = {0, 1, 0, 1, 0, 1};
        TransitionAnalysis analysis = TransitionAnalysis.compute(seq);

        assertEquals(1.0, analysis.oscillation(0, 1), EPSILON);
        assertEquals(1.0, analysis.oscillation(1, 0), EPSILON);
    }

    @Test
    void testTwoCellOscillationWithOtherTransitions() {
        // Transitions:
        // (0, 1) - involves 0 and 1
        // (1, 2) - involves 1
        // (2, 0) - involves 0
        // (3, 4) - involves neither 0 nor 1
        // Sequence: 0 -> 1 -> 2 -> 0, then 3 -> 4
        long[][] counts = new long[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES];
        counts[0][1] = 1L;
        counts[1][2] = 1L;
        counts[2][0] = 1L;
        counts[3][4] = 1L;

        TransitionAnalysis analysis = TransitionAnalysis.computeFromCounts(counts);

        // Transitions involving 0 or 1:
        // (0,1) + (1,2) + (2,0) = 3 transitions. (3,4) is excluded.
        // Numerator (0,1) + (1,0) = 1 + 0 = 1.
        // Oscillation(0, 1) = 1 / 3
        assertEquals(1.0 / 3.0, analysis.oscillation(0, 1), EPSILON);
    }

    @Test
    void testOscillationZeroDenominator() {
        long[][] counts = new long[DecisionGrid.TOTAL_STATES][DecisionGrid.TOTAL_STATES];
        counts[3][4] = 5L;
        TransitionAnalysis analysis = TransitionAnalysis.computeFromCounts(counts);

        assertEquals(0.0, analysis.oscillation(0, 1), EPSILON);
    }

    @Test
    void testOscillationSameStateThrows() {
        TransitionAnalysis analysis = TransitionAnalysis.compute(new int[] {0, 1});
        assertThrows(IllegalArgumentException.class, () -> analysis.oscillation(0, 0));
    }

    @Test
    void testComputeWithList() {
        TransitionAnalysis analysis = TransitionAnalysis.compute(List.of(0, 1, 0, 1));
        assertEquals(1.0, analysis.oscillation(0, 1), EPSILON);
    }
}
