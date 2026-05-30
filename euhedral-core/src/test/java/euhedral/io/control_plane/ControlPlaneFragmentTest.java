package euhedral.io.control_plane;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import euhedral.io.config.CloneConfig;
import euhedral.io.config.SchedulingConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ControlPlaneFragmentTest {
    private CloneConfig cloneConfig() {
        CloneConfig clone = mock(CloneConfig.class);

        when(clone.shardName()).thenReturn("test");
        when(clone.coreId()).thenReturn(0);
        when(clone.getCpuSet()).thenReturn(new int[]{0});

        return clone;
    }

    private SchedulingConfig config() {
        return new SchedulingConfig(
                cloneConfig(),
                64,
                128,
                false,
                new SchedulingConfig.IdleCyclePolicy(
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
        ControlPlaneFragment manager = new ControlPlaneFragment(config());

        assertNotNull(manager);
        assertFalse(manager.isStarted());
    }

    @Test
    void shouldInitializeMinimumConcurrency() {
        ControlPlaneFragment manager = new ControlPlaneFragment(config());

        assertEquals(64, manager.currentConcurrency);
        assertEquals(64, manager.currentRate);
        assertEquals(64, manager.effectiveConcurrencyLimit);
    }

    @Test
    void shouldCreateRequiredInfrastructure() {
        ControlPlaneFragment manager = new ControlPlaneFragment(config());

        assertNotNull(manager.output());
        assertNotNull(manager.completeChannel());

        assertNotNull(manager.buffer);
        assertNotNull(manager.bufferWrapper);
        assertNotNull(manager.outputStream);
    }

    @Test
    void shouldEnableDrainMode() {
        ControlPlaneFragment manager = new ControlPlaneFragment(config());

        manager.setDrainMode(true);

        assertTrue(manager.drainMode);
    }

    @Test
    void shouldFirstTouchWithoutFailure() {
        ControlPlaneFragment manager = new ControlPlaneFragment(config());

        assertDoesNotThrow(manager::firstTouch);
    }

    @Test
    void shouldCloneManager() {
        ControlPlaneFragment manager = new ControlPlaneFragment(config());

        CloneConfig cloneConfig = cloneConfig();

        ControlPlaneFragment cloned = manager.clone(cloneConfig);

        assertNotNull(cloned);
        assertNotSame(manager, cloned);
    }

    @Test
    void shouldPropagateCloneConfig() {
        CloneConfig cloneConfig = cloneConfig();

        SchedulingConfig config = config();

        SchedulingConfig cloned =
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
        ControlPlaneFragment manager = new ControlPlaneFragment(config());

        double pressure = manager.getPressure();

        assertTrue(pressure >= 0.0);
        assertTrue(pressure <= 1.0);
    }

    @Test
    void shouldBeInitiallyDrained() {
        ControlPlaneFragment manager = new ControlPlaneFragment(config());

        assertTrue(manager.isDrained());
    }

    @Test
    void shouldCloseSafely() {
        ControlPlaneFragment manager = new ControlPlaneFragment(config());

        assertDoesNotThrow(manager::close);
    }


}