package io.euhedral_execution.data_structures.queues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PartitionedSpscQueueTest {

    @Test
    void singleThreadOfferDrain() {
        int chunkSize = 128;
        PartitionedSpscQueue<Integer> q =
                new PartitionedSpscQueue<>(1, chunkSize, 2);

        for (int i = 1; i <= chunkSize * 4; i++) {
            assertTrue(q.offer(i));
        }

        final int[] drained = new int[] {0};
        Consumer<Integer> consumer = val -> {
            if (val != ++drained[0]) {
                fail("Corruption! Last Value: " + drained[0] + " Current: " + val);
            }
        };
        q.drain(consumer, chunkSize * 4);

        assertEquals(chunkSize * 4, drained[0]);
    }

    @Test
    void queueCyclesWithoutDeadlockingOnePartition() throws Exception {
        cycle(1);
    }

    @Test
    void queueCyclesWithoutDeadlockingFourPartitions() throws Exception {
        cycle(4);
    }

    private static void cycle(int partitions) throws Exception {
        PartitionedSpscQueue<Long> q =
                new PartitionedSpscQueue<>(partitions, 256, 4);
        int batch = 2048;

        Consumer<Long> consumer = val -> {
        };
        ExecutorService exec = Executors.newFixedThreadPool(2);
        for (int x = 0; x < 10_000; x++) {
            CountDownLatch end = new CountDownLatch(1);

            LongAdder offered = new LongAdder();
            LongAdder drained = new LongAdder();

            exec.submit(() -> {
                for (int i = 0; i < batch; i++) {
                    long v = ThreadLocalRandom.current().nextLong();

                    while (!q.offer(v, System.nanoTime())) {
                        Thread.onSpinWait();
                    }
                    offered.increment();
                }
            });

            exec.submit(() -> {
                while (drained.sum() < batch) {
                    long count = q.drain(consumer, batch);
                    drained.add(count);
                    Thread.yield();
                }
                end.countDown();
            });
            end.await(5, TimeUnit.SECONDS);

            assertEquals(batch, drained.sum(),
                    String.format("Iteration: %d Consumed: %d Offered: %d", x, drained.sum(),
                            offered.sum()));
        }
    }

    @Test
    void spscNoLossNoDuplication() throws Exception {
        int batch = 50_000;

        PartitionedSpscQueue<Long> q =
                new PartitionedSpscQueue<>(4, 512, 4);

        ExecutorService exec = Executors.newFixedThreadPool(2);

        LongAdder consumed = new LongAdder();

        exec.submit(() -> {
            for (int i = 0; i < batch; i++) {
                long val = i;

                while (!q.offer(ThreadLocalRandom.current().nextLong(), val)) {
                    Thread.yield();
                }
            }
        });

        CountDownLatch consLatch = new CountDownLatch(1);
        Consumer<Long> consumer = val -> consumed.increment();

        exec.submit(() -> {
            while (consumed.sum() < batch) {
                q.drain(consumer, 512);
            }
            consLatch.countDown();
        });

        consLatch.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertEquals(batch, consumed.sum(), q.toString());
    }
}