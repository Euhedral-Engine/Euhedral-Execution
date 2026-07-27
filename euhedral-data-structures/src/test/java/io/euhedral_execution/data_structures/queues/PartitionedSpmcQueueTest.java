package io.euhedral_execution.data_structures.queues;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PartitionedSpmcQueueTest {

    @Test
    void stopConditionLeavesTheRemainingValuesQueued() {
        PartitionedSpmcQueue<Integer> queue = new PartitionedSpmcQueue<>(1, 4, 1);
        for (int i = 0; i < 6; i++) {
            queue.offer(0, i);
        }

        List<Integer> drained = new ArrayList<>();
        long count = queue.drain(0, drained::add, value -> value == 2, 6);

        assertEquals(2, count);
        assertEquals(List.of(0, 1), drained);
        assertEquals(4, queue.sizeLong());
        assertEquals(2, queue.peek());
    }

    @Test
    void multipleConsumersDoNotLoseOrDuplicateValues() throws Exception {
        int consumerCount = 4;
        int itemCount = 1_024;
        PartitionedSpmcQueue<Integer> queue = new PartitionedSpmcQueue<>(1, 8, 2);
        for (int i = 0; i < itemCount; i++) {
            assertTrue(queue.offer(0, i));
        }

        ExecutorService executor = Executors.newFixedThreadPool(consumerCount);
        CountDownLatch start = new CountDownLatch(1);
        Set<Integer> consumed = ConcurrentHashMap.newKeySet();
        AtomicInteger consumedCount = new AtomicInteger();
        List<Future<?>> consumers = new ArrayList<>();

        try {
            for (int i = 0; i < consumerCount; i++) {
                consumers.add(executor.submit(() -> {
                    await(start);
                    long deadline = System.nanoTime() + SECONDS.toNanos(5);
                    while (consumedCount.get() < itemCount) {
                        Integer value = queue.poll(0);
                        if (value != null) {
                            assertTrue(consumed.add(value), "duplicate " + value);
                            consumedCount.incrementAndGet();
                        } else {
                            assertBefore(deadline, "consumer timed out");
                            Thread.onSpinWait();
                        }
                    }
                }));
            }

            start.countDown();
            for (Future<?> consumer : consumers) {
                consumer.get(5, SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, SECONDS));
        }

        assertEquals(itemCount, consumed.size());
        for (int i = 0; i < itemCount; i++) {
            assertTrue(consumed.contains(i));
        }
        assertFalse(consumed.contains(itemCount));
        assertTrue(queue.isEmpty());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, SECONDS), "start latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while awaiting test start", e);
        }
    }

    private static void assertBefore(long deadline, String message) {
        assertTrue(System.nanoTime() < deadline, message);
    }
}
