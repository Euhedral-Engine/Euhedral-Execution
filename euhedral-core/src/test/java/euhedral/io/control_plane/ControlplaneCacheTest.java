package euhedral.io.control_plane;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import euhedral.hardware_utils.common.SystemUtilization.CoreSnapshot;
import euhedral.hardware_utils.common.SystemUtilization.CpuSnapshot;
import euhedral.io.config.CacheConfig;
import euhedral.io.config.CloneConfig;
import euhedral.io.flow_control.UpstreamQueue;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.CacheManager;
import euhedral.io.utils.DrainBuffer;
import euhedral.queues.PartitionedArrayQueue;
import euhedral.queues.common.PartitionedQueue;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("resource")
class ControlplaneCacheTest {
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

    private ControlplaneCache manager() {
        return new ControlplaneCache(config());
    }

    @AfterEach
    void cleanup() {
        ControlplaneCache.CACHES.clear();
        ControlplaneCache.UPSTREAM.remove();
        UpstreamQueue.UP_QUEUE.remove();
    }

    // ----- Construction -----
    @Test
    void shouldConstructManager() {
        ControlplaneCache manager = manager();

        assertNotNull(manager);
    }

    @Test
    void shouldInitializeFields() {
        ControlplaneCache manager = manager();

        assertNotNull(manager.handles);
        assertNotNull(manager.getFillRecorder());
        assertNotNull(manager.getFillBytesRecorder());
        assertNotNull(manager.queueRing);
        assertNotNull(manager.partitionStats);
        assertTrue(manager.partitionStats.length > 0);
    }

    // ----- First Touch -----

    @Test
    void shouldPrimeQueues() {
        ControlplaneCache manager = manager();

        manager.firstTouch();

        assertTrue(manager.primed);
    }

    @Test
    void shouldAllowRepeatedFirstTouch() {
        ControlplaneCache manager = manager();

        assertDoesNotThrow(() -> {
            manager.firstTouch();
            manager.firstTouch();
        });
    }

    // ----- Management -----

    @Test
    void shouldAddHandle() {
        ControlplaneCache manager = manager();

        ControlplaneCache.DownstreamHandle handle =
                new ControlplaneCache.DownstreamHandle(1, () -> 0.0);

        manager.addHandle(handle);

        assertNotNull(manager.handles);
        assertEquals(1, manager.handles.size());
    }

    @Test
    void shouldRemoveHandle() {
        ControlplaneCache manager = manager();

        ControlplaneCache.DownstreamHandle handle =
                new ControlplaneCache.DownstreamHandle(1, () -> 0.0);

        manager.addHandle(handle);

        manager.removeHandle(1);

        assertNotNull(manager.handles);
        assertEquals(0, manager.handles.size());
    }

    // ----- Queue State -----

    @Test
    void shouldBeInitiallyEmpty() {
        ControlplaneCache manager = manager();

        assertTrue(manager.isEmpty());
    }

    @Test
    void shouldBeInitiallyDrained() {
        ControlplaneCache manager = manager();

        assertTrue(manager.isDrained());
    }

    @Test
    void shouldBeDrainedAfterFirstTouch() {
        ControlplaneCache manager = manager();
        manager.firstTouch();
        assertTrue(manager.isDrained());
    }

    @Test
    void shouldReturnZeroTotalCountInitially() {
        ControlplaneCache manager = manager();

        assertEquals(0, manager.getTotalCount());
    }

    @Test
    void shouldSetDrainMode() {
        ControlplaneCache manager = manager();

        manager.setDrainMode(true);

        assertTrue(manager.getDrainFlag().get());
    }

    // ----- CAS Locks -----

    @Test
    void shouldAcquireAndReleasePartitionLock() {
        ControlplaneCache manager = manager();

        boolean acquired = manager.acquireLock(0);

        assertTrue(acquired);

        manager.releaseLock(0);

        assertFalse(manager.partitionLocks[0]);
    }

    @Test
    void shouldFailAcquireWhenAlreadyLocked() {
        ControlplaneCache manager = manager();

        assertTrue(manager.acquireLock(0));
        assertFalse(manager.acquireLock(0));
    }

    // ----- Max Queue -----

    @Test
    void shouldCalculateMaxQueuedBytes() {
        ControlplaneCache manager = manager();

        long max = manager.getMaxQueuedBytes();

        assertTrue(max >= 0);
    }

    @Test
    void shouldCalculateProportionalMaxQueuedBytes() {
        ControlplaneCache manager = manager();

        long max = manager.getProportionalMaxQueuedBytes();

        assertTrue(max >= 0);
    }

    @Test
    void shouldIgnoreNullSnapshot() {
        ControlplaneCache manager = manager();

        assertDoesNotThrow(() -> manager.update(null));
    }

    @Test
    void shouldUpdateCapFactorFromSnapshot() throws Exception {
        ControlplaneCache manager = manager();

        ControlplaneCache.DownstreamHandle handle =
                new ControlplaneCache.DownstreamHandle(0, () -> 0.5);

        manager.addHandle(handle);

        CpuSnapshot cpu = mock(CpuSnapshot.class);

        CoreSnapshot snapshot = mock(CoreSnapshot.class);

        when(snapshot.memoryLimit()).thenReturn(1024L * 1024L);

        manager.update(snapshot);

        assertTrue(manager.capFactor.getAcquire() > 0.0);
    }

    // ----- Cloning -----

    @Test
    void shouldCloneManager() {
        ControlplaneCache manager = manager();

        CacheManager cloned = manager.clone(cloneConfig());

        assertNotNull(cloned);
    }

    @Test
    void shouldReuseCacheForSameHash() {
        ControlplaneCache manager = manager();

        CacheManager one = manager.clone(cloneConfig());
        CacheManager two = manager.clone(cloneConfig());

        assertSame(one, two);
    }

    // ----- Downstream Handle -----

    @Test
    void shouldCreateDownstreamHandle() throws Exception {
        Callable<Double> pressure = () -> 0.25;

        ControlplaneCache.DownstreamHandle handle =
                new ControlplaneCache.DownstreamHandle(2, pressure);

        assertEquals(2, handle.cpu);
        assertEquals(0.25, handle.downstreamPressure.call());
    }

    @Test
    void shouldRecordDrainMetrics() {
        ControlplaneCache.DownstreamHandle handle =
                new ControlplaneCache.DownstreamHandle(0, () -> 0.0);

        PartitionedQueue<AbstractFrame> queue =
                new PartitionedArrayQueue<>(64);

        DrainBuffer buffer =
                new DrainBuffer(queue, 64, false);

        assertDoesNotThrow(() ->
                handle.record(10, 2048, buffer));
    }

    // ----- PartitionStats -----

    @Test
    void shouldResetPartitionStats() {
        ControlplaneCache.PartitionStats stats =
                new ControlplaneCache.PartitionStats();

        stats.weight = 999;
        stats.quotaBytes = 555;
        stats.lastBytesDrained = 123;

        stats.reset();

        assertEquals(1024, stats.weight);
        assertEquals(0, stats.quotaBytes);
        assertEquals(0, stats.lastBytesDrained);
    }

    @Test
    void shouldCloseSafely() {
        ControlplaneCache manager = manager();

        assertDoesNotThrow(manager::close);
    }
}