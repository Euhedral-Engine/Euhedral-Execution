package euhedral.io;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import euhedral.io.config.CloneConfig;
import euhedral.io.config.ExecutionManagerConfig;
import org.junit.jupiter.api.Test;

class ExecutionManagerTest {
    private CloneConfig cloneConfig() {
        CloneConfig clone = mock(CloneConfig.class);

        when(clone.shardName()).thenReturn("test");
        when(clone.coreId()).thenReturn(0);
        when(clone.getCpuSet()).thenReturn(new int[]{0});

        return clone;
    }

    private ExecutionManagerConfig config() {
        return new ExecutionManagerConfig(
                cloneConfig(),
                64,
                128,
                false,
                new ExecutionManagerConfig.IdleCyclePolicy(
                        0.4,
                        0.8,
                        1.0,
                        Duration.ofNanos(10_000)
                ),
                null,
                null
        );
    }

    @Test
    void shouldConstructWithoutCloneConfig() {
        ExecutionManager manager = new ExecutionManager(config());

        assertNotNull(manager);
        assertFalse(manager.isStarted());
    }

    @Test
    void shouldInitializeMinimumConcurrency() {
        ExecutionManager manager = new ExecutionManager(config());

        assertEquals(64, manager.currentConcurrency);
        assertEquals(64, manager.currentRate);
        assertEquals(64, manager.effectiveConcurrencyLimit);
    }

    @Test
    void shouldCreateRequiredInfrastructure() {
        ExecutionManager manager = new ExecutionManager(config());

        assertNotNull(manager.output());
        assertNotNull(manager.completeChannel());

        assertNotNull(manager.buffer);
        assertNotNull(manager.bufferWrapper);
        assertNotNull(manager.outputStream);
    }

    @Test
    void shouldEnableDrainMode() {
        ExecutionManager manager = new ExecutionManager(config());

        manager.setDrainMode(true);

        assertTrue(manager.drainMode);
    }

    @Test
    void shouldFirstTouchWithoutFailure() {
        ExecutionManager manager = new ExecutionManager(config());

        assertDoesNotThrow(manager::firstTouch);
    }

    @Test
    void shouldCloneManager() {
        ExecutionManager manager = new ExecutionManager(config());

        CloneConfig cloneConfig = cloneConfig();

        ExecutionManager cloned = manager.clone(cloneConfig);

        assertNotNull(cloned);
        assertNotSame(manager, cloned);
    }

    @Test
    void shouldPropagateCloneConfig() {
        CloneConfig cloneConfig = cloneConfig();

        ExecutionManagerConfig config = config();

        ExecutionManagerConfig cloned =
                config.clone(cloneConfig);

        assertSame(cloneConfig, cloned.cloneConfig());

        assertEquals(
                config.minConcurrency(),
                cloned.minConcurrency()
        );

        assertEquals(
                config.maxUpdateInterval(),
                cloned.maxUpdateInterval()
        );
    }

    @Test
    void shouldReturnPressureWithinBounds() {
        ExecutionManager manager = new ExecutionManager(config());

        double pressure = manager.getPressure();

        assertTrue(pressure >= 0.0);
        assertTrue(pressure <= 1.0);
    }

    @Test
    void shouldBeInitiallyDrained() {
        ExecutionManager manager = new ExecutionManager(config());

        assertTrue(manager.isDrained());
    }

    @Test
    void shouldCloseSafely() {
        ExecutionManager manager = new ExecutionManager(config());

        assertDoesNotThrow(manager::close);
    }


}