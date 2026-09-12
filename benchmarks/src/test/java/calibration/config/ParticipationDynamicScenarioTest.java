package calibration.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ParticipationDynamicScenarioTest {
    @Test
    void eachScenarioKeepsFourWindowsPerPhaseAndCombinedReverseIsExact() {
        for (var scenario : ParticipationDynamicScenario.values()) {
            for (int i = 0; i < 4; i++) assertEquals(scenario.phase(0, 8), scenario.phase(i, 8));
            for (int i = 4; i < 8; i++) assertEquals(scenario.phase(7, 8), scenario.phase(i, 8));
        }
        assertEquals(
                ParticipationDynamicScenario.COMBINED.phase(0, 8),
                ParticipationDynamicScenario.COMBINED_HIGH_TO_LOW.phase(7, 8));
        assertEquals(
                ParticipationDynamicScenario.COMBINED.phase(7, 8),
                ParticipationDynamicScenario.COMBINED_HIGH_TO_LOW.phase(0, 8));
        assertThrows(IllegalArgumentException.class, () -> ParticipationDynamicScenario.COMBINED.phase(8, 8));
    }
}
