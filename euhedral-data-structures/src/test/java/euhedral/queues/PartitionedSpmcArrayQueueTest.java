package euhedral.queues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import org.junit.jupiter.api.Test;

class PartitionedSpmcArrayQueueTest {

    @Test
    void singleThreadOfferDrain() {
        PartitionedSpmcArrayQueue<Integer> q = new PartitionedSpmcArrayQueue<>(4, 128, false);

        for (int i = 0; i < 1000; i++) {
            if (i < 128) {
                assertTrue(q.offer(0, i));
            } else {
                assertFalse(q.offer(0, i));
            }
        }

        final int[] drained = new int[] {-1};
        QueueConsumer<Integer> consumer = (val) -> {
            if (val != ++drained[0] || val >= 128) {
                fail("Corruption! Last Value: " + drained[0] + " Current: " + val);
            }
        };
        q.drain(consumer, 1000);

        assertEquals(127, drained[0]);
    }

    @Test
    void queueCyclesWithoutDeadlockingOnePartition() throws Exception {
        cycle(1);
    }

    @Test
    void queueCyclesWithoutDeadlockingFourPartitions() throws Exception {
        cycle(4);
    }

    private void cycle(int partitions) throws Exception {
        PartitionedSpmcArrayQueue<Long> q =
                new PartitionedSpmcArrayQueue<>(partitions, 4096, false);

        QueueConsumer<Long> consumer = (val) -> {
        };
        ExecutorService exec = Executors.newFixedThreadPool(9);
        for (int x = 0; x < 10; x++) {
            CountDownLatch end = new CountDownLatch(8);

            LongAdder offered = new LongAdder();
            LongAdder drained = new LongAdder();

            int batch = 800_000;
            exec.submit(() -> {
                for (int j = 0; j < batch; j++) {
                    long v = ThreadLocalRandom.current().nextLong();

                    while (!q.offer(v, System.nanoTime())) {
                        Thread.onSpinWait();
                    }
                    offered.increment();
                }
            });

            for (int i = 0; i < 8; i++) {
                exec.submit(() -> {
                    while (drained.sum() < batch) {
                        int count = q.drain(consumer, 4096);
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
    void multiConsumerNoDuplication() throws Exception {
        int consumers = 4;
        int batch = 100_000;

        PartitionedSpmcArrayQueue<Integer> q = new PartitionedSpmcArrayQueue<>(4, 512, false);

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
        QueueConsumer<Integer> consumer = consumed::add;

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

    @Test
    void retireAndDrainCompletes() {
        PartitionedSpmcArrayQueue<Integer> q = new PartitionedSpmcArrayQueue<>(2, 64, true);

        int inserted = 0;

        for (int i = 0; i < 1000; i++) {
            if (q.offer(0, i)) {
                inserted++;
            }
        }

        while (q.offer(0, 9999)) {
            inserted++;
        }

        final int[] total = new int[1];
        QueueConsumer<Integer> consumer = (val) -> {
            total[0]++;
        };
        while (!q.isEmpty()) {
            q.drain(consumer, inserted);
        }

        assertEquals(inserted, total[0]);
    }
}