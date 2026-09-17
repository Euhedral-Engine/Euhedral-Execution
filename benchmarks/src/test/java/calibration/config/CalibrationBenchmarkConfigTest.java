package calibration.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.config.IdlePolicy;
import org.junit.jupiter.api.Test;

class CalibrationBenchmarkConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void onlyEnablesObservationForRemainingCallbacks() throws Exception {
        CalibrationBenchmarkConfig disabled = read(false, false);
        CalibrationBenchmarkConfig progress = read(true, false);
        CalibrationBenchmarkConfig complete = read(false, true);

        assertFalse(disabled.observes());
        assertTrue(progress.observes());
        assertTrue(complete.observes());
        assertEquals(new IdlePolicy(15_000L, 1_000_000L), disabled.toIdlePolicy());
    }

    @Test
    void rejectsRemovedObserverConfiguration() {
        String json = validJson(false, false).replace("\"observeBatchProgress\":false", "\"observeCycleStart\":true");

        assertThrows(Exception.class, () -> mapper.readValue(json, CalibrationBenchmarkConfig.class));
    }

    @Test
    void rejectsConfigurationWithoutSources() {
        String json = validJson(false, false).replace("\"parallelSources\":2", "\"parallelSources\":0");

        assertThrows(Exception.class, () -> mapper.readValue(json, CalibrationBenchmarkConfig.class));
    }

    @Test
    void rejectsNegativeWork() {
        String json = validJson(false, false).replace("\"workUnits\":0", "\"workUnits\":-1");

        assertThrows(Exception.class, () -> mapper.readValue(json, CalibrationBenchmarkConfig.class));
    }

    private CalibrationBenchmarkConfig read(boolean progress, boolean complete) throws Exception {
        return mapper.readValue(validJson(progress, complete), CalibrationBenchmarkConfig.class);
    }

    private static String validJson(boolean progress, boolean complete) {
        return """
                {"cpuSet":[2,4],"parallelSources":2,"orderedSources":0,"workUnits":0,
                 "randomizeWork":false,"totalRequiredExecutions":100,"invocationTimeoutMillis":1000,
                 "rawSampleLimit":8,"observeBatchProgress":%s,"observeBatchComplete":%s,
                 "idleParkNs":15000,"contentionHalfLifeNanos":1000000}
                """.formatted(progress, complete);
    }
}
