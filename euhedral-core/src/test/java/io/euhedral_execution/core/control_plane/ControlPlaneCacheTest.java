package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.euhedral_execution.core.config.CacheConfig;
import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.flow_control.UpstreamQueue;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CpuSnapshot;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("resource")
class ControlPlaneCacheTest {

    private CloneConfig cloneConfig() {
        CloneConfig clone = mock(CloneConfig.class);

        when(clone.shardName()).thenReturn("test");
        when(clone.coreId()).thenReturn(0);
        when(clone.getCpuSet()).thenReturn(new int[]{0});

        return clone;
    }

    private CacheConfig config() {
        return new CacheConfig(
                cloneConfig(),
                0.7,
                1,
                4,
                64,
                null,
                null
        );
    }

    private ControlPlaneCache manager() {
        return new CPCImpl(config());
    }

    @AfterEach
    void cleanup() {
        UpstreamQueue.UP_QUEUE.remove();
    }

    // ----- Construction -----
    @Test
    void shouldConstructManager() {
        ControlPlaneCache manager = manager();

        assertNotNull(manager);
    }

    @Test
    void shouldInitializeFields() {
        ControlPlaneCache manager = manager();

        assertNotNull(manager.getLocalCache());
    }

    // ----- First Touch -----

    @Test
    void shouldPrimeQueues() {
        ControlPlaneCache manager = manager();

        manager.firstTouch();

        assertTrue(manager.primed);
    }

    @Test
    void shouldAllowRepeatedFirstTouch() {
        ControlPlaneCache manager = manager();

        assertDoesNotThrow(() -> {
            manager.firstTouch();
            manager.firstTouch();
        });
    }

    // ----- Queue State -----

    @Test
    void shouldBeInitiallyDrained() {
        ControlPlaneCache manager = manager();

        assertTrue(manager.isDrained());
    }

    @Test
    void shouldBeDrainedAfterFirstTouch() {
        ControlPlaneCache manager = manager();
        manager.firstTouch();
        assertTrue(manager.isDrained());
    }

    @Test
    void shouldReturnZeroTotalCountInitially() {
        ControlPlaneCache manager = manager();

        assertEquals(0, manager.getLocalCacheCount());
    }

    @Test
    void shouldSetDrainMode() {
        ControlPlaneCache manager = manager();

        manager.setDrainMode(true);

        assertTrue(manager.getDrainFlag().get());
    }

    // ----- Max Queue -----

    @Test
    void shouldIgnoreNullSnapshot() {
        ControlPlaneCache manager = manager();

        assertDoesNotThrow(() -> manager.update(null));
    }

    @Test
    void shouldUpdateCapFactorFromSnapshot() {
        ControlPlaneCache manager = new CPCImpl(config());

        CoreSnapshot snapshot = mock(CoreSnapshot.class);
        CpuSnapshot cpuSnap = mock(CpuSnapshot.class);
        when(snapshot.cpuSnapshots()).thenReturn(new CpuSnapshot[]{cpuSnap});
        when(cpuSnap.pressure()).thenReturn(0.50);

        when(snapshot.memoryLimit()).thenReturn(1024L * 1024L);

        manager.update(snapshot);

        assertTrue(manager.capFactor > 0.0);
    }

    @Test
    void shouldCloseSafely() {
        ControlPlaneCache manager = manager();

        assertDoesNotThrow(manager::close);
    }

    private static class CPCImpl extends ControlPlaneCache {

        public CPCImpl(@NonNull CacheConfig cacheConfig) {
            super(cacheConfig);
        }

        @Override
        public CloneableObject clone(CloneConfig cloneConfig) {
            return null;
        }
    }
}