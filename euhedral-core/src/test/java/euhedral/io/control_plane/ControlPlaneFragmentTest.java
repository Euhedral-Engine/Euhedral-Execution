package euhedral.io.control_plane;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import euhedral.io.config.CacheConfig;
import euhedral.io.config.CloneConfig;
import euhedral.io.config.FragmentConfig;
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

    private CacheConfig cConfig() {
        return new CacheConfig(cloneConfig(), 0.7, 4, 1, 4, null, null);
    }

    private FragmentConfig sConfig() {
        return new FragmentConfig(
                cloneConfig(),
                false,
                new FragmentConfig.IdleCyclePolicy(
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
        ControlPlaneFragment manager = new ControlPlaneFragment(cConfig(), sConfig());

        assertNotNull(manager);
        assertFalse(manager.isStarted());
    }

    @Test
    void shouldCreateRequiredInfrastructure() {
        ControlPlaneFragment manager = new ControlPlaneFragment(cConfig(), sConfig());

        assertNotNull(manager.output());
        assertNotNull(manager.completeChannel());

        assertNotNull(manager.getCache());
        assertNotNull(manager.outputStream);
    }

    @Test
    void shouldEnableDrainMode() {
        ControlPlaneFragment manager = new ControlPlaneFragment(cConfig(), sConfig());

        manager.setDrainMode(true);

        assertTrue(manager.drainMode);
    }

    @Test
    void shouldFirstTouchWithoutFailure() {
        ControlPlaneFragment manager = new ControlPlaneFragment(cConfig(), sConfig());

        assertDoesNotThrow(manager::firstTouch);
    }

    @Test
    void shouldCloneManager() {
        ControlPlaneFragment manager = new ControlPlaneFragment(cConfig(), sConfig());

        CloneConfig cloneConfig = cloneConfig();

        ControlPlaneFragment cloned = manager.clone(cloneConfig);

        assertNotNull(cloned);
        assertNotSame(manager, cloned);
    }

    @Test
    void shouldPropagateCloneConfig() {
        CloneConfig cloneConfig = cloneConfig();

        FragmentConfig config = sConfig();

        FragmentConfig cloned =
                config.clone(cloneConfig);

        assertSame(cloneConfig, cloned.cloneConfig());
    }

    @Test
    void shouldBeInitiallyDrained() {
        ControlPlaneFragment manager = new ControlPlaneFragment(cConfig(), sConfig());

        assertTrue(manager.isDrained());
    }

    @Test
    void shouldCloseSafely() {
        ControlPlaneFragment manager = new ControlPlaneFragment(cConfig(), sConfig());

        assertDoesNotThrow(manager::close);
    }


}