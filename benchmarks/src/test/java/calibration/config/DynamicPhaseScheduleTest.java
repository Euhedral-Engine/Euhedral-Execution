package calibration.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class DynamicPhaseScheduleTest {
    @Test
    void transitionsAndReturnPreserveExactStimulusAndWarmupStaysInInitialPhase() {
        var schedule = new DynamicPhaseSchedule(List.of(
                new DynamicPhaseSchedule.Phase("scarce", 6, 0, 1),
                new DynamicPhaseSchedule.Phase("plentiful", 6, 0, 15),
                new DynamicPhaseSchedule.Phase("return", 6, 0, 1)));
        schedule.validate(18, 15, CalibrationLifecycleMode.CONTINUOUS);
        assertEquals(1, schedule.phase(5, false).enabledSources());
        assertEquals(15, schedule.phase(6, false).enabledSources());
        assertEquals(1, schedule.phase(12, false).enabledSources());
        assertEquals(1, schedule.phase(7, true).enabledSources());
        assertThrows(IllegalArgumentException.class, () -> schedule.phase(18, false));
        assertThrows(
                IllegalArgumentException.class, () -> schedule.validate(17, 15, CalibrationLifecycleMode.CONTINUOUS));
        assertThrows(
                IllegalArgumentException.class, () -> schedule.validate(18, 14, CalibrationLifecycleMode.CONTINUOUS));
        assertThrows(IllegalArgumentException.class, () -> schedule.validate(18, 15, CalibrationLifecycleMode.RESET));
    }

    @Test
    void parsesBodyTransitionsAndRejectsInvalidPhases() {
        var schedule = DynamicPhaseSchedule.parse("""
                {"phases":[{"name":"low","windows":6,"workUnits":0,"enabledSources":1},
                           {"name":"high","windows":6,"workUnits":576,"enabledSources":1},
                           {"name":"low-again","windows":6,"workUnits":0,"enabledSources":1}]}
                """);
        assertEquals(0, schedule.phase(5, false).workUnits());
        assertEquals(576, schedule.phase(6, false).workUnits());
        assertEquals(0, schedule.phase(12, false).workUnits());
        assertThrows(IllegalArgumentException.class, () -> new DynamicPhaseSchedule.Phase("bad", 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> DynamicPhaseSchedule.parse("{}"));
    }
}
