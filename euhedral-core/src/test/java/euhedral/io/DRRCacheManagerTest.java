package euhedral.io;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.Callable;

import euhedral.hardware_utils.common.SystemUtilization.CoreSnapshot;
import euhedral.hardware_utils.common.SystemUtilization.CpuSnapshot;
import euhedral.io.config.CloneConfig;
import euhedral.io.config.DRRConfig;
import euhedral.io.flow_control.UpstreamQueue;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.CacheManager;
import euhedral.io.utils.DrainBuffer;
import euhedral.queues.PartitionedArrayQueue;
import euhedral.queues.common.PartitionedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("resource")
class DRRCacheManagerTest {
    private CloneConfig cloneConfig() {
        CloneConfig clone = mock(CloneConfig.class);

        when(clone.shardName()).thenReturn("test");
        when(clone.coreId()).thenReturn(0);
        when(clone.getCpuSet()).thenReturn(new int[]{0});

        return clone;
    }

    private DRRConfig config() {
        return new DRRConfig(
                cloneConfig(),
                0.7,
                1,
                4,
                64,
                2.0,
                null,
                null
        );
    }

    private DRRCacheManager manager() {
        return new DRRCacheManager(config());
    }

    @AfterEach
    void cleanup() {
        DRRCacheManager.CACHES.clear();
        DRRCacheManager.UPSTREAM.remove();
        UpstreamQueue.UP_QUEUE.remove();
    }

    // ----- Construction -----
    @Test
    void shouldConstructManager() {
        DRRCacheManager manager = manager();

        assertNotNull(manager);
    }

    @Test
    void shouldInitializeFields() {
        DRRCacheManager manager = manager();

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
        DRRCacheManager manager = manager();

        manager.firstTouch();

        assertTrue(manager.primed);
    }

    @Test
    void shouldAllowRepeatedFirstTouch() {
        DRRCacheManager manager = manager();

        assertDoesNotThrow(() -> {
            manager.firstTouch();
            manager.firstTouch();
        });
    }

    // ----- Management -----

    @Test
    void shouldAddHandle() {
        DRRCacheManager manager = manager();

        DRRCacheManager.DownstreamHandle handle =
                new DRRCacheManager.DownstreamHandle(1, () -> 0.0);

        manager.addHandle(handle);

        assertNotNull(manager.handles);
        assertEquals(1, manager.handles.size());
    }

    @Test
    void shouldRemoveHandle() {
        DRRCacheManager manager = manager();

        DRRCacheManager.DownstreamHandle handle =
                new DRRCacheManager.DownstreamHandle(1, () -> 0.0);

        manager.addHandle(handle);

        manager.removeHandle(1);

        assertNotNull(manager.handles);
        assertEquals(0, manager.handles.size());
    }

    // ----- Queue State -----

    @Test
    void shouldBeInitiallyEmpty() {
        DRRCacheManager manager = manager();

        assertTrue(manager.isEmpty());
    }

    @Test
    void shouldBeInitiallyDrained() {
        DRRCacheManager manager = manager();

        assertTrue(manager.isDrained());
    }

    @Test
    void shouldBeDrainedAfterFirstTouch() {
        DRRCacheManager manager = manager();
        manager.firstTouch();
        assertTrue(manager.isDrained());
    }

    @Test
    void shouldReturnZeroTotalCountInitially() {
        DRRCacheManager manager = manager();

        assertEquals(0, manager.getTotalCount());
    }

    @Test
    void shouldSetDrainMode() {
        DRRCacheManager manager = manager();

        manager.setDrainMode(true);

        assertTrue(manager.getDrainFlag().get());
    }

    // ----- CAS Locks -----

    @Test
    void shouldAcquireAndReleasePartitionLock() {
        DRRCacheManager manager = manager();

        boolean acquired = manager.acquireLock(0);

        assertTrue(acquired);

        manager.releaseLock(0);

        assertFalse(manager.partitionLocks[0]);
    }

    @Test
    void shouldFailAcquireWhenAlreadyLocked() {
        DRRCacheManager manager = manager();

        assertTrue(manager.acquireLock(0));
        assertFalse(manager.acquireLock(0));
    }

    // ----- Max Queue -----

    @Test
    void shouldCalculateMaxQueuedBytes() {
        DRRCacheManager manager = manager();

        long max = manager.getMaxQueuedBytes();

        assertTrue(max >= 0);
    }

    @Test
    void shouldCalculateProportionalMaxQueuedBytes() {
        DRRCacheManager manager = manager();

        long max = manager.getProportionalMaxQueuedBytes();

        assertTrue(max >= 0);
    }

    @Test
    void shouldIgnoreNullSnapshot() {
        DRRCacheManager manager = manager();

        assertDoesNotThrow(() -> manager.update(null));
    }

    @Test
    void shouldUpdateCapFactorFromSnapshot() throws Exception {
        DRRCacheManager manager = manager();

        DRRCacheManager.DownstreamHandle handle =
                new DRRCacheManager.DownstreamHandle(0, () -> 0.5);

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
        DRRCacheManager manager = manager();

        CacheManager cloned = manager.clone(cloneConfig());

        assertNotNull(cloned);
    }

    @Test
    void shouldReuseCacheForSameHash() {
        DRRCacheManager manager = manager();

        CacheManager one = manager.clone(cloneConfig());
        CacheManager two = manager.clone(cloneConfig());

        assertSame(one, two);
    }

    // ----- Downstream Handle -----

    @Test
    void shouldCreateDownstreamHandle() throws Exception {
        Callable<Double> pressure = () -> 0.25;

        DRRCacheManager.DownstreamHandle handle =
                new DRRCacheManager.DownstreamHandle(2, pressure);

        assertEquals(2, handle.cpu);
        assertEquals(0.25, handle.downstreamPressure.call());
    }

    @Test
    void shouldRecordDrainMetrics() {
        DRRCacheManager.DownstreamHandle handle =
                new DRRCacheManager.DownstreamHandle(0, () -> 0.0);

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
        DRRCacheManager.PartitionStats stats =
                new DRRCacheManager.PartitionStats();

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
        DRRCacheManager manager = manager();

        assertDoesNotThrow(manager::close);
    }
}