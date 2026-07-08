package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.euhedral_execution.core.config.CacheConfig;
import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentConfig;
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
                cConfig(),
                4096,
                null,
                null
        );
    }

    @Test
    void shouldConstructWithoutCloneConfig() {
        ControlPlaneFragment manager = new ControlPlaneFragment(sConfig());

        assertNotNull(manager);
        assertFalse(manager.isStarted());
    }

    @Test
    void shouldCreateRequiredInfrastructure() {
        ControlPlaneFragment manager = new ControlPlaneFragment(sConfig());

        assertNotNull(manager.output());

        assertNotNull(manager.getCache());
        assertNotNull(manager.outputStream);
    }

    @Test
    void shouldEnableDrainMode() {
        ControlPlaneFragment manager = new ControlPlaneFragment(sConfig());

        manager.setDrainMode(true);

        assertTrue(manager.drainMode);
    }

    @Test
    void shouldFirstTouchWithoutFailure() {
        ControlPlaneFragment manager = new ControlPlaneFragment(sConfig());

        assertDoesNotThrow(manager::firstTouch);
    }

    @Test
    void shouldCloneManager() {
        ControlPlaneFragment manager = new ControlPlaneFragment(sConfig());

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
        ControlPlaneFragment manager = new ControlPlaneFragment(sConfig());

        assertTrue(manager.isDrained());
    }

    @Test
    void shouldCloseSafely() {
        ControlPlaneFragment manager = new ControlPlaneFragment(sConfig());

        assertDoesNotThrow(manager::close);
    }


}