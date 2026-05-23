package euhedral.queues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.Test;

class PartitionedSpscArrayQueueTest {

    @Test
    void singleThreadOfferDrain() {
        PartitionedSpscArrayQueue<Integer> q = new PartitionedSpscArrayQueue<>(4, 128, false);

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
        PartitionedSpscArrayQueue<Long> q =
                new PartitionedSpscArrayQueue<>(partitions, 4096, false);

        QueueConsumer<Long> consumer = (val) -> {
        };
        ExecutorService exec = Executors.newFixedThreadPool(2);
        for (int x = 0; x < 10; x++) {
            CountDownLatch end = new CountDownLatch(1);

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

            exec.submit(() -> {
                while (drained.sum() < batch) {
                    int count = q.drain(consumer, 4096);
                    drained.add(count);
                    Thread.yield();
                }
                end.countDown();
            });
            end.await(5, TimeUnit.SECONDS);
            assertEquals(batch, drained.sum(),
                    String.format("Iteration: %d Consumed: %d Offered: %d\n%s", x, drained.sum(),
                            offered.sum(), q));
        }
    }

    @Test
    void retireAndDrainCompletes() {
        PartitionedSpscArrayQueue<Integer> q = new PartitionedSpscArrayQueue<>(2, 64, true);

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