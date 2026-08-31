package calibration.infra;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CalibrationExecutorTest {
    @Test
    void dynamicBodyPhaseUpdatesEveryLiveCloneWithoutReconstruction() {
        CalibrationExecutor template = new CalibrationExecutor(0, false);
        CalibrationExecutor firstWorker = template.hookOnClone(1);
        CalibrationExecutor secondWorker = template.hookOnClone(2);

        template.setWorkUnitLimit(768);

        assertEquals(768, template.workUnitLimit());
        assertEquals(768, firstWorker.workUnitLimit());
        assertEquals(768, secondWorker.workUnitLimit());
    }
}
