package io.euhedral_execution.core.control_plane;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.euhedral_execution.core.config.CacheConfig;
import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLongArray;
import io.euhedral_execution.hardware_utils.SystemInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Isolated
@Execution(ExecutionMode.SAME_THREAD)
class ControlPlaneCachePartitionTest {

    private final List<TestCache> caches = new ArrayList<>();
    private final int fixtureCpu = SystemInfo.getCpuSet().length() - 1;
    private ControlPlaneCache[] registry;
    private ControlPlaneCache[] savedRegistry;

    @BeforeEach
    void saveRegistry() throws ReflectiveOperationException {
        var field = ControlPlaneCache.class.getDeclaredField("WORK_STEAL");
        field.setAccessible(true);
        registry = (ControlPlaneCache[]) field.get(null);
        savedRegistry = registry.clone();
    }

    @AfterEach
    void restoreRegistry() {
        try {
            for (TestCache cache : caches) {
                cache.close();
            }
        } finally {
            System.arraycopy(savedRegistry, 0, registry, 0, registry.length);
        }
    }

    @Test
    void drainSharesOneBudgetAcrossPartitions() {
        TestCache cache = cache(2, false);
        AbstractFrame first = new TestFrame(0);
        AbstractFrame second = new TestFrame(1);
        AbstractFrame third = new TestFrame(Long.MIN_VALUE);
        AbstractFrame fourth = new TestFrame(-1);
        cache.push(first);
        cache.push(second);
        cache.push(third);
        cache.push(fourth);

        List<AbstractFrame> drained = new ArrayList<>();
        long count = cache.drain(drained::add, 3);

        assertEquals(3, drained.size(), "One drain must share its budget across all partitions");
        assertEquals(3, count);
        assertSame(first, drained.get(0));
        assertSame(second, drained.get(1));
        assertSame(third, drained.get(2));
        assertEquals(1, cache.getLocalCacheCount());
        drained.clear();
        assertEquals(1, cache.drain(drained::add, 3));
        assertEquals(1, drained.size());
        assertSame(fourth, drained.getFirst());
        assertTrue(cache.isDrained());
    }

    @Test
    void emptyFinalPartitionsDoNotEraseEarlierDrainCount() {
        TestCache cache = cache(3, true);
        AbstractFrame first = new TestFrame(0);
        AbstractFrame second = new TestFrame(Long.MIN_VALUE);
        cache.push(first);
        cache.push(second);
        List<AbstractFrame> drained = new ArrayList<>();

        assertEquals(2, cache.drain(drained::add, 10));
        assertEquals(2, drained.size());
        assertSame(first, drained.getFirst());
        assertSame(second, drained.getLast());
        assertEquals(0, cache.getLocalCacheCount());
        assertTrue(cache.isDrained());
    }

    @Test
    void nonpositiveLimitLeavesFramesAndCallbacksUntouched() {
        TestCache cache = cache(2, true);
        AbstractFrame first = new TestFrame(0);
        AbstractFrame second = new TestFrame(-1);
        cache.push(first);
        cache.push(second);
        for (long limit : new long[] {0, -1, Long.MIN_VALUE}) {
            assertEquals(
                    0,
                    cache.drain(
                            frame -> {
                                throw new AssertionError("Unexpected consumption");
                            },
                            frame -> {
                                throw new AssertionError("Unexpected stop callback");
                            },
                            limit));
            assertEquals(2, cache.getLocalCacheCount());
        }
        List<AbstractFrame> drained = new ArrayList<>();
        assertEquals(2, cache.drain(drained::add, 2));
        assertSame(first, drained.getFirst());
        assertSame(second, drained.getLast());
    }

    @Test
    void stoppedPartitionPreservesItsHeadWhileOtherPartitionsProgress() {
        TestCache cache = cache(2, true);
        AbstractFrame stopped = new TestFrame(0);
        AbstractFrame behind = new TestFrame(1);
        AbstractFrame other = new TestFrame(-1);
        cache.push(stopped);
        cache.push(behind);
        cache.push(other);
        List<AbstractFrame> drained = new ArrayList<>();

        assertEquals(1, cache.drain(drained::add, frame -> frame == stopped, 3));
        assertEquals(1, drained.size());
        assertSame(other, drained.getFirst());
        assertEquals(2, cache.getLocalCacheCount());
        drained.clear();
        assertEquals(2, cache.drain(drained::add, 3));
        assertSame(stopped, drained.getFirst());
        assertSame(behind, drained.getLast());
        assertTrue(cache.isDrained());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownerAndThiefCannotConsumeTheSamePartition(boolean thiefHoldsLock) throws Exception {
        TestCache thief = cache(1, true);
        TestCache victim = cache(1, true);
        AbstractFrame first = unorderedFrame();
        AbstractFrame second = unorderedFrame();
        victim.push(first);
        victim.push(second);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var holder = executor.submit(() -> {
                Consumer<AbstractFrame> callback = frame -> {
                    assertSame(first, frame);
                    entered.countDown();
                    await(release);
                };
                return thiefHoldsLock ? thief.workSteal(callback, 1, fixtureCpu) : victim.drain(callback, 1);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var contender = executor.submit(() -> {
                Consumer<AbstractFrame> callback = frame -> {
                    throw new AssertionError("Contender entered the owned partition");
                };
                return thiefHoldsLock ? victim.drain(callback, 1) : thief.workSteal(callback, 1, fixtureCpu);
            });
            assertEquals(0L, contender.get(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(1L, holder.get(5, TimeUnit.SECONDS));
            List<AbstractFrame> drained = new ArrayList<>();
            assertEquals(1, thief.workSteal(drained::add, 2, fixtureCpu));
            assertEquals(1, drained.size());
            assertSame(second, drained.getFirst());
            assertTrue(victim.isDrained());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void stopCallbackFailureReleasesLockAndPreservesHead() {
        TestCache cache = cache(1, true);
        AbstractFrame frame = unorderedFrame();
        cache.push(frame);
        var failure = new IllegalStateException("stop failed");
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> cache.drain(
                                value -> {
                                    throw new AssertionError("Stop failure must precede consumption");
                                },
                                value -> {
                                    assertSame(frame, value);
                                    throw failure;
                                },
                                1)));
        List<AbstractFrame> drained = new ArrayList<>();
        assertEquals(1, cache.workSteal(drained::add, 1, fixtureCpu));
        assertSame(frame, drained.getFirst());
        assertTrue(cache.isDrained());
    }

    @Test
    void consumerCallbackFailureReleasesPartitionLock() throws ReflectiveOperationException {
        TestCache cache = cache(1, true);
        AbstractFrame frame = unorderedFrame();
        cache.push(frame);
        var failure = new IllegalStateException("consumer failed");
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> cache.drain(
                                value -> {
                                    assertSame(frame, value);
                                    throw failure;
                                },
                                1)));
        // Queue callback recovery is a separate contract; inspect only lock release here.
        var field = ControlPlaneCache.class.getDeclaredField("pLocks");
        field.setAccessible(true);
        var locks = (PaddedAtomicLongArray) field.get(cache);
        assertTrue(locks.compareAndSet(0, 0, 1));
        locks.setRelease(0, 0);
    }

    @Test
    void frameQuotaDoesNotBoundGrowableQueueOccupancy() {
        TestCache cache = cache(2, false);
        long quota = cache.getFrameQuota();
        assertEquals(1024, quota, "Tiny fixture budget selects minimum-sized chunks");
        List<AbstractFrame> expected = new ArrayList<>();
        for (int i = 0; i < quota + 17; i++) {
            AbstractFrame frame = new TestFrame(i);
            expected.add(frame);
            cache.push(frame);
        }
        assertEquals(expected.size(), cache.getLocalCacheCount());
        assertTrue(cache.getLocalCacheCount() > cache.getMaxLocalCacheCount());
        List<AbstractFrame> drained = new ArrayList<>();
        assertEquals(expected.size(), cache.drain(drained::add, Long.MAX_VALUE));
        assertEquals(expected.size(), drained.size());
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i), drained.get(i));
        }
        assertTrue(cache.isDrained());
    }

    @Test
    void stealingLeavesOrderedHeadForOwner() {
        TestCache thief = cache(1, true);
        TestCache victim = cache(1, true);
        AbstractFrame ordered = new TestFrame(0);
        AbstractFrame unordered = unorderedFrame();
        victim.push(ordered);
        victim.push(unordered);
        List<AbstractFrame> drained = new ArrayList<>();
        assertEquals(0, thief.workSteal(drained::add, 2, fixtureCpu));
        assertTrue(drained.isEmpty());
        assertEquals(2, victim.getLocalCacheCount());
        assertEquals(2, victim.drain(drained::add, 2));
        assertSame(ordered, drained.getFirst());
        assertSame(unordered, drained.getLast());
        assertTrue(victim.isDrained());
    }

    @Test
    void closedCacheRemainsRegisteredAndStealableCharacterization() {
        TestCache thief = cache(1, true);
        TestCache victim = cache(1, true);
        AbstractFrame frame = unorderedFrame();
        victim.push(frame);
        victim.close();
        assertTrue(victim.isClosed());
        assertSame(victim, registry[fixtureCpu]);
        List<AbstractFrame> drained = new ArrayList<>();
        assertEquals(1, thief.workSteal(drained::add, 1, fixtureCpu));
        assertSame(frame, drained.getFirst());
        assertTrue(victim.isDrained());
    }

    private static AbstractFrame unorderedFrame() {
        AbstractFrame frame = new TestFrame(0);
        frame.randomizeHash(1);
        assertFalse(frame.isOrdered());
        return frame;
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private TestCache cache(int partitions, boolean workSteal) {
        int cpu = fixtureCpu;
        assertTrue(cpu >= 0, "The topology must expose a CPU for the real cache-layout lookup");
        CloneConfig clone = mock(CloneConfig.class);
        when(clone.shardName()).thenReturn("partition-test");
        when(clone.coreId()).thenReturn(0);
        when(clone.getCpuSet()).thenReturn(new int[] {cpu});
        TestCache cache = new TestCache(new CacheConfig(clone, 0.000001, partitions, 0, 4, workSteal, null, null), cpu);
        caches.add(cache);
        return cache;
    }

    private static final class TestFrame extends AbstractFrame {
        private TestFrame(long hash) {
            super(hash);
        }
    }

    private static final class TestCache extends ControlPlaneCache {
        private TestCache(CacheConfig config, int cpu) {
            super(config, cpu, false);
        }

        @Override
        public CloneableObject clone(CloneConfig config) {
            throw new UnsupportedOperationException("Test fixture is not cloneable");
        }
    }
}
