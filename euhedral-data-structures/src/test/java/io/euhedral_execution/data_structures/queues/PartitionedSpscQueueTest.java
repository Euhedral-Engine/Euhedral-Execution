package io.euhedral_execution.data_structures.queues;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class PartitionedSpscQueueTest {

    @Test
    void drainsAcrossPartitionsUpToTheRequestedLimit() {
        PartitionedSpscQueue<Integer> queue = new PartitionedSpscQueue<>(3, 4, 2);

        queue.offer(0, 1);
        queue.offer(0, 2);
        queue.offer(1, 3);
        queue.offer(2, 4);

        List<Integer> drained = new ArrayList<>();
        assertEquals(3, queue.drain(drained::add, 3));
        assertEquals(List.of(1, 2, 3), drained);
        assertEquals(1, queue.sizeLong());
        assertEquals(3, queue.partitions());
        assertEquals(2, queue.maxPooledChunks());
    }

    @Test
    void singleProducerAndConsumerPreserveOrderAcrossChunkRollover() throws Exception {
        int itemCount = 1_024;
        PartitionedSpscQueue<Integer> queue = new PartitionedSpscQueue<>(1, 8, 2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> consumed = new ArrayList<>(itemCount);

        try {
            Future<?> producer = executor.submit(() -> {
                await(start);
                for (int i = 0; i < itemCount; i++) {
                    assertTrue(queue.offer(0, i));
                }
            });
            Future<?> consumer = executor.submit(() -> {
                await(start);
                long deadline = System.nanoTime() + SECONDS.toNanos(5);
                while (consumed.size() < itemCount) {
                    Integer value = queue.poll(0);
                    if (value != null) {
                        consumed.add(value);
                    } else {
                        assertBefore(deadline, "consumer timed out");
                        Thread.onSpinWait();
                    }
                }
            });

            start.countDown();
            producer.get(5, SECONDS);
            consumer.get(5, SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, SECONDS));
        }

        assertEquals(itemCount, consumed.size());
        for (int i = 0; i < itemCount; i++) {
            assertEquals(i, consumed.get(i));
        }
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
