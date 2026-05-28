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

class PartitionedMpscArrayQueueTest {

    @Test
    void singleThreadOfferDrain() {
        PartitionedMpscArrayQueue<Integer> q =
                new PartitionedMpscArrayQueue<>(4, 128, false);

        for (int i = 0; i < 1000; i++) {
            if (i < 128) {
                assertTrue(q.offer(0, i));
            } else {
                assertFalse(q.offer(0, i));
            }
        }

        final int[] drained = new int[]{-1};
        QueueConsumer<Integer> consumer = (val) -> {
            if (val != ++drained[0] || val >= 128) {
                fail("Corruption! Last Value: " + drained[0] + " Current: " + val);
            }
        };
        q.drain(consumer, 1000);

        assertEquals(127, drained[0], "Failure: \n" + q.getState());
    }

    @Test
    void queueCyclesWithoutDeadlocking() {
        PartitionedMpscArrayQueue<Long> q =
                new PartitionedMpscArrayQueue<>(1, 4096, false);

        QueueConsumer<Long> consumer = (val) -> {
        };
        ExecutorService exec = Executors.newFixedThreadPool(16);
        for (int x = 0; x < 10; x++) {
            int batch = 100_000;
            LongAdder drained = new LongAdder();
            for (int i = 0; i < 8; i++) {
                exec.submit(() -> {
                    for (int j = 0; j < batch; j++) {
                        long v = ThreadLocalRandom.current().nextLong();

                        while (!q.offer(v, System.nanoTime())) {
                            Thread.onSpinWait();
                        }
                    }
                });
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (drained.sum() < batch * 8 && System.nanoTime() < deadline) {
                long count = q.drain(consumer, 4096);
                drained.add(count);
                Thread.yield();
            }
            assertEquals(800_000, drained.sum(), "Failure: \n" + q.getState());
        }
    }

    @Test
    void multiProducerNoLossNoDuplication() {
        int producers = 8;
        int perProducer = 50_000;
        int total = producers * perProducer;

        PartitionedMpscArrayQueue<Long> q =
                new PartitionedMpscArrayQueue<>(8, 1024, false);

        ExecutorService exec = Executors.newFixedThreadPool(producers);

        Set<Long> produced = ConcurrentHashMap.newKeySet();
        Set<Long> consumed = ConcurrentHashMap.newKeySet();

        CountDownLatch latch = new CountDownLatch(producers);

        for (int p = 0; p < producers; p++) {
            final int id = p;
            exec.submit(() -> {
                for (int i = 0; i < perProducer; i++) {
                    long val = (((long) id) << 32) | i;

                    while (!q.offer(id % 8, val)) {
                        Thread.yield();
                    }

                    produced.add(val);
                }
                latch.countDown();
            });
        }

        QueueConsumer<Long> consumer = (val) -> {
            if (!consumed.add(val)) {
                fail("Duplicate: " + val);
            }
        };

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while ((latch.getCount() > 0 || consumed.size() < total)
                && System.nanoTime() < deadline) {
            q.drain(consumer, perProducer);
        }

        exec.shutdownNow();

        assertEquals(produced.size(), consumed.size());
        assertEquals(produced, consumed);
    }

    @Test
    void retireAndDrainCompletes() {
        PartitionedMpscArrayQueue<Integer> q =
                new PartitionedMpscArrayQueue<>(2, 64, true);

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