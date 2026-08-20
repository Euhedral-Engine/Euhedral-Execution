package calibration.comparisons;

import static org.junit.jupiter.api.Assertions.assertEquals;

import calibration.comparisons.schema.StateComparability;
import org.junit.jupiter.api.Test;

class StateComparabilityCalculatorTest {

    @Test
    void classifiesComparablePhase10LowDistanceGeometry() {
        assertEquals(
                StateComparability.STATE_COMPARABLE, StateComparabilityCalculator.classify(0.0, -0.10, 0.01, 0.074));
    }

    @Test
    void classifiesIntermediateMovementAsShifted() {
        assertEquals(StateComparability.STATE_SHIFTED, StateComparabilityCalculator.classify(0.0, -0.40, 0.20, 0.370));
    }

    @Test
    void classifiesPhase10LargeDistanceGeometryAsDivergent() {
        assertEquals(
                StateComparability.STATE_DIVERGENT, StateComparabilityCalculator.classify(0.0, -0.93, 0.89, 0.818));
    }

    @Test
    void differentOpportunityGeometryIsDivergentEvenWhenOccupancyIsClose() {
        assertEquals(StateComparability.STATE_DIVERGENT, StateComparabilityCalculator.classify(0.125, 0.0, 0.0, 0.05));
    }
}
