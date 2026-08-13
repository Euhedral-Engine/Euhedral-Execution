package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.euhedral_execution.core.config.CacheConfig;
import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.UpstreamQueue;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CpuSnapshot;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("resource")
class ControlPlaneCacheTest {

    private CloneConfig cloneConfig() {
        CloneConfig clone = mock(CloneConfig.class);

        when(clone.shardName()).thenReturn("test");
        when(clone.coreId()).thenReturn(0);
        when(clone.getCpuSet()).thenReturn(new int[] {0});

        return clone;
    }

    private CacheConfig config() {
        return new CacheConfig(cloneConfig(), 0.7, 1, 4, 64, null, null);
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
        when(snapshot.cpuSnapshots()).thenReturn(new CpuSnapshot[] {cpuSnap});
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

    @Test
    void shouldApplyEwmaHysteresisOnPressureSpikeAndRecovery() {
        ControlPlaneCache manager = manager();

        CoreSnapshot highPressure = mock(CoreSnapshot.class);
        CpuSnapshot highCpu = mock(CpuSnapshot.class);
        when(highPressure.cpuSnapshots()).thenReturn(new CpuSnapshot[] {highCpu});
        when(highCpu.pressure()).thenReturn(0.80);

        manager.update(highPressure);
        double capAfterHigh = manager.getCapFactor();

        CoreSnapshot lowPressure = mock(CoreSnapshot.class);
        CpuSnapshot lowCpu = mock(CpuSnapshot.class);
        when(lowPressure.cpuSnapshots()).thenReturn(new CpuSnapshot[] {lowCpu});
        when(lowCpu.pressure()).thenReturn(0.00);

        manager.update(lowPressure);
        double capAfterLow = manager.getCapFactor();

        assertTrue(capAfterHigh < 1.0);
        assertTrue(capAfterLow >= capAfterHigh);
    }

    @Test
    void testConstructorValidationAndNullCloneConfig() {
        // Partitions <= 0 throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> new CacheConfig(cloneConfig(), 0.7, 0, 4, 64, null, null));

        // MemoryBudget <= 0 or non-finite throws IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> new CacheConfig(cloneConfig(), 0.0, 1, 4, 64, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CacheConfig(cloneConfig(), -0.5, 1, 4, 64, null, null));
        assertThrows(
                IllegalArgumentException.class, () -> new CacheConfig(cloneConfig(), Double.NaN, 1, 4, 64, null, null));

        // Null cloneConfig creates uninitialized lightweight cache
        CacheConfig noCloneConfig = new CacheConfig(null, 0.7, 1, 4, 64, null, null);
        CPCImpl noCloneCache = new CPCImpl(noCloneConfig);

        assertNull(noCloneCache.getLocalCache());
        assertEquals(-1, noCloneCache.getCore());
        assertEquals(0, noCloneCache.getFrameQuota());
        assertEquals(0, noCloneCache.getMaxLocalCacheCount());
        assertEquals("ControlPlaneCache", ControlPlaneCache.getName(noCloneConfig));
        assertEquals(0L, noCloneCache.clearLocalCacheOnOwnerThread());
    }

    @Test
    void testGetName() {
        CloneConfig mockClone = mock(CloneConfig.class);
        when(mockClone.shardName()).thenReturn("MainShard");
        when(mockClone.coreId()).thenReturn(3);

        CacheConfig configWithClone = new CacheConfig(mockClone, 0.7, 1, 4, 64, null, null);
        assertEquals("MainShard-ControlPlaneCache-3", ControlPlaneCache.getName(configWithClone));

        CacheConfig configWithoutClone = new CacheConfig(null, 0.7, 1, 4, 64, null, null);
        assertEquals("ControlPlaneCache", ControlPlaneCache.getName(configWithoutClone));
    }

    @Test
    void testSetDownstreamMapping() {
        ControlPlaneCache cache = manager();
        assertFalse(cache.setDownstreamMapping(new BitSet(), new LatticeEdge[0]));
    }

    @Test
    void testInputHandling() {
        ControlPlaneCache cache = manager();

        // LatticeEdge input calls addUpstream
        LatticeEdge edge = new LatticeEdge(new AtomicBoolean(false));
        cache.input(edge);

        // Generic LatticeSource input calls stream.addDownstream(cache)
        LatticeSource mockSource = mock(LatticeSource.class);
        cache.input(mockSource);
        verify(mockSource).addDownstream(cache);
    }

    @Test
    void testPushDrainAndCount() {
        ControlPlaneCache cache = manager();
        TestFrame frame = new TestFrame(42L);

        assertTrue(cache.isDrained());
        cache.push(frame);

        assertFalse(cache.isDrained());
        assertEquals(1, cache.getLocalCacheCount());

        List<AbstractFrame> drained = new ArrayList<>();
        long count = cache.drain(drained::add, 10);
        assertEquals(1, count);
        assertEquals(1, drained.size());
        assertEquals(frame, drained.getFirst());
        assertTrue(cache.isDrained());
        assertEquals(0, cache.getLocalCacheCount());
    }

    @Test
    void testClearLocalCacheOnOwnerThread() {
        ControlPlaneCache cache = manager();
        cache.push(new TestFrame(100L));
        cache.push(new TestFrame(200L));
        assertEquals(2, cache.getLocalCacheCount());
        assertFalse(cache.isDrained());

        long cleared = cache.clearLocalCacheOnOwnerThread();
        assertEquals(2, cleared);
        assertEquals(0, cache.getLocalCacheCount());
        assertTrue(cache.isDrained());
    }

    @Test
    void testPullAndUpstreamPullValidation() {
        ControlPlaneCache cache = manager();

        assertEquals(0, cache.pull(0));
        assertEquals(0, cache.pull(-10));

        UpstreamQueue queue = mock(UpstreamQueue.class);
        Consumer<AbstractFrame> consumer = frame -> {};
        assertEquals(0, cache.upstreamPull(queue, consumer, 0));
        assertEquals(0, cache.upstreamPull(queue, consumer, -5));

        assertEquals(0, cache.drain(consumer, 0));
        assertEquals(0, cache.drain(consumer, -1));
    }

    @Test
    void testGetCoreAndCapFactor() {
        ControlPlaneCache cache = manager();
        assertEquals(0, cache.getCore());
        assertEquals(1.0, cache.getCapFactor(), 0.001);

        long maxCacheCount = cache.getMaxLocalCacheCount();
        assertTrue(maxCacheCount > 0);
    }

    static class TestFrame extends AbstractFrame {
        TestFrame(long idHash) {
            super(idHash);
        }
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
