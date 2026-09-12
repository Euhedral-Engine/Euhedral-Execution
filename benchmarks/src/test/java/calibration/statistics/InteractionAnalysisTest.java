package calibration.statistics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class InteractionAnalysisTest {

    private static final double EPSILON = 1e-9;

    @Test
    void testPurelyAdditiveZeroInteraction() {
        double tBase = 100.0;
        double tA = 120.0; // +20
        double tB = 130.0; // +30
        double tAB = 150.0; // +50 (exactly additive: 100 + 20 + 30)

        double interaction = InteractionAnalysis.compute(tBase, tA, tB, tAB);
        assertEquals(0.0, interaction, EPSILON);
    }

    @Test
    void testSynergisticPositiveInteraction() {
        double tBase = 100.0;
        double tA = 120.0;
        double tB = 130.0;
        double tAB = 170.0; // +70 (super-additive)

        double interaction = InteractionAnalysis.compute(tBase, tA, tB, tAB);
        assertEquals(20.0, interaction, EPSILON);
    }

    @Test
    void testAntagonisticNegativeInteraction() {
        double tBase = 100.0;
        double tA = 120.0;
        double tB = 130.0;
        double tAB = 135.0; // +35 (sub-additive)

        double interaction = InteractionAnalysis.compute(tBase, tA, tB, tAB);
        assertEquals(-15.0, interaction, EPSILON);
    }

    @Test
    void testNonFiniteRejection() {
        assertThrows(IllegalArgumentException.class, () -> InteractionAnalysis.compute(Double.NaN, 10.0, 10.0, 10.0));
        assertThrows(
                IllegalArgumentException.class,
                () -> InteractionAnalysis.compute(10.0, Double.POSITIVE_INFINITY, 10.0, 10.0));
    }
}
