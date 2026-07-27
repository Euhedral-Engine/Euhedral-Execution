package io.euhedral_execution.data_structures.queues;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class PartitionedMpscQueueTest {

    @Test
    void clearOnlyRemovesTheSelectedPartition() {
        PartitionedMpscQueue<Integer> queue = new PartitionedMpscQueue<>(2, 4, 1);

        queue.offer(0, 1);
        queue.offer(0, 2);
        queue.offer(1, 3);
        queue.clear(0);

        assertTrue(queue.isEmpty(0));
        assertEquals(1, queue.size(1));
        assertEquals(3, queue.poll(1));
        assertTrue(queue.isEmpty());
    }

    @Test
    void multipleProducersDoNotLoseOrDuplicateValues() throws Exception {
        int producerCount = 4;
        int itemsPerProducer = 256;
        int itemCount = producerCount * itemsPerProducer;
        PartitionedMpscQueue<Long> queue = new PartitionedMpscQueue<>(1, 8, 2);
        ExecutorService executor = Executors.newFixedThreadPool(producerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> producers = new ArrayList<>();
        Set<Long> consumed = new HashSet<>(itemCount);

        try {
            for (int producerId = 0; producerId < producerCount; producerId++) {
                int id = producerId;
                producers.add(executor.submit(() -> {
                    await(start);
                    for (int i = 0; i < itemsPerProducer; i++) {
                        assertTrue(queue.offer(0, value(id, i)));
                    }
                }));
            }

            start.countDown();
            long deadline = System.nanoTime() + SECONDS.toNanos(5);
            while (consumed.size() < itemCount) {
                queue.drain(0, value -> assertTrue(consumed.add(value), "duplicate " + value),
                        itemCount);
                if (consumed.size() < itemCount) {
                    assertBefore(deadline, "consumer timed out");
                    Thread.onSpinWait();
                }
            }
            for (Future<?> producer : producers) {
                producer.get(5, SECONDS);
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
