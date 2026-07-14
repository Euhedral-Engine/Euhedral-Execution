package io.euhedral_execution.data_structures.queues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class PartitionedSpmcQueueTest {

    @Test
    void singleThreadOfferDrain() {
        int chunkSize = 128;
        PartitionedSpmcQueue<Integer> q =
                new PartitionedSpmcQueue<>(1, chunkSize, 2);

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
        PartitionedSpmcQueue<Long> q =
                new PartitionedSpmcQueue<>(partitions, 1024, 4);
        int batch = 100_000;
        int consumers = 8;

        Consumer<Long> consumer = val -> {
        };
        ExecutorService exec = Executors.newFixedThreadPool(consumers + 1);
        for (int x = 0; x < 20; x++) {
            CountDownLatch end = new CountDownLatch(consumers);

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

            for (int i = 0; i < consumers; i++) {
                exec.submit(() -> {
                    while (drained.sum() < batch) {
                        long count = q.drain(consumer, 4096);
                        drained.add(count);
                        Thread.yield();
                    }
                    end.countDown();
                });
            }
            end.await(5, TimeUnit.SECONDS);

            assertEquals(batch, drained.sum(),
                    String.format("Iteration: %d Consumed: %d Offered: %d\n%s", x, drained.sum(),
                            offered.sum(), q));
        }
    }

    @Test
    void multiConsumerNoLossNoDuplication() throws Exception {
        int consumers = 4;
        int batch = 50_000;

        PartitionedSpmcQueue<Integer> q =
                new PartitionedSpmcQueue<>(4, 512, 4);

        ExecutorService exec = Executors.newFixedThreadPool(1 + consumers);

        Set<Integer> consumed = ConcurrentHashMap.newKeySet();

        exec.submit(() -> {
            for (int i = 0; i < batch; i++) {
                while (!q.offer(ThreadLocalRandom.current().nextLong(), i)) {
                    Thread.yield();
                }
            }
        });

        CountDownLatch consLatch = new CountDownLatch(consumers);
        Consumer<Integer> consumer = consumed::add;

        for (int c = 0; c < consumers; c++) {
            exec.submit(() -> {
                while (consumed.size() < batch) {
                    q.drain(consumer, 512);
                }
                consLatch.countDown();
            });
        }

        consLatch.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertEquals(batch, consumed.size(), q.toString());
    }

}