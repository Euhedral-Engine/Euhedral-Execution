package io.euhedral_execution.benchmarks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class BenchmarkLoggingTest {

    @Test
    void shouldUseLogbackAndKeepBenchmarkInfoVisible() {
        assertEquals(
                "ch.qos.logback.classic.LoggerContext",
                LoggerFactory.getILoggerFactory().getClass().getName());
        assertTrue(LoggerFactory.getLogger(BenchRunner.class).isInfoEnabled());
        assertFalse(LoggerFactory.getLogger("euhedral.core").isInfoEnabled());
        assertFalse(LoggerFactory.getLogger("euhedral.hardware_utils").isInfoEnabled());
    }
}
