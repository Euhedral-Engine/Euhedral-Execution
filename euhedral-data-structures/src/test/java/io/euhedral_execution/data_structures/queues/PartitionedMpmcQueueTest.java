package io.euhedral_execution.data_structures.queues;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

class PartitionedMpmcQueueTest {

    @Test
    void queueWideOperationsCoverEveryPartition() {
        PartitionedMpmcQueue<Integer> queue = new PartitionedMpmcQueue<>(3, 4, 2);
        queue.offer(2, 3);
        queue.offer(0, 1);
        queue.offer(1, 2);

        assertEquals(3, queue.sizeLong());
        assertEquals(1, queue.peek());
        assertEquals(1, queue.poll());
        queue.clear();
        assertTrue(queue.isEmpty());
        assertThrows(UnsupportedOperationException.class, queue::iterator);
    }

    @Test
    void multipleProducersAndConsumersDoNotLoseOrDuplicateValues() throws Exception {
        int producerCount = 4;
        int consumerCount = 4;
        int itemsPerProducer = 256;
        int itemCount = producerCount * itemsPerProducer;
        PartitionedMpmcQueue<Long> queue = new PartitionedMpmcQueue<>(1, 8, 2);
        ExecutorService executor = Executors.newFixedThreadPool(producerCount + consumerCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger producersRemaining = new AtomicInteger(producerCount);
        AtomicInteger consumedCount = new AtomicInteger();
        Set<Long> consumed = ConcurrentHashMap.newKeySet();
        List<Future<?>> tasks = new ArrayList<>();

        try {
            for (int producerId = 0; producerId < producerCount; producerId++) {
                int id = producerId;
                tasks.add(executor.submit(() -> {
                    await(start);
                    try {
                        for (int i = 0; i < itemsPerProducer; i++) {
                            assertTrue(queue.offer(0, value(id, i)));
                        }
                    } finally {
                        producersRemaining.decrementAndGet();
                    }
                }));
            }
            for (int i = 0; i < consumerCount; i++) {
                tasks.add(executor.submit(() -> {
                    await(start);
                    long deadline = System.nanoTime() + SECONDS.toNanos(5);
                    while (producersRemaining.get() > 0 || consumedCount.get() < itemCount) {
                        Long value = queue.poll(0);
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
            for (Future<?> task : tasks) {
                task.get(5, SECONDS);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, SECONDS));
        }

        assertEquals(itemCount, consumed.size());
        for (int producerId = 0; producerId < producerCount; producerId++) {
            for (int i = 0; i < itemsPerProducer; i++) {
                assertTrue(consumed.contains(value(producerId, i)));
            }
        }
        assertTrue(queue.isEmpty());
    }

    private static long value(int producerId, int itemId) {
        return ((long) producerId << 32) | itemId;
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
