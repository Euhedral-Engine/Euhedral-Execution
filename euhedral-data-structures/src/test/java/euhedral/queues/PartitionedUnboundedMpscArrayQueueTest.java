package euhedral.queues;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import euhedral.experimental.UnboundedMpscQueue;
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

class PartitionedUnboundedMpscArrayQueueTest {
    @Test
    void singleThreadOfferDrain() {
        int chunkSize = 128;
        UnboundedMpscQueue<Integer> q =
                new UnboundedMpscQueue<>(chunkSize);

        for (int i = 1; i <= chunkSize * 4; i++) {
            assertTrue(q.offer(i));
        }

        final int[] drained = new int[]{0};
        Consumer<Integer> consumer = (val) -> {
            if (val != ++drained[0]) {
                fail("Corruption! Last Value: " + drained[0] + " Current: " + val);
            }
        };
        q.drain(consumer, chunkSize * 4);

        assertEquals(chunkSize * 4, drained[0]);
    }

    @Test
    void queueCyclesWithoutDeadlockingOnePartition() {
        cycle(1);
    }

    @Test
    void queueCyclesWithoutDeadlockingFourPartitions() {
        cycle(4);
    }

    private void cycle(int partitions) {
        UnboundedMpscQueue<Long> q =
                new UnboundedMpscQueue<>(4096);
        int batch = 100_000;

        Consumer<Long> consumer = (val) -> {
        };
        ExecutorService exec = Executors.newFixedThreadPool(16);
        for (int x = 0; x < 20; x++) {
            LongAdder drained = new LongAdder();
            for (int i = 0; i < 8; i++) {
                exec.submit(() -> {
                    for (int j = 0; j < batch; j++) {
                        long v = ThreadLocalRandom.current().nextLong();

                        while (!q.offer(System.nanoTime())) {
                            Thread.onSpinWait();
                        }
                    }
                });
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (drained.sum() < batch * 8 && System.nanoTime() < deadline) {
                long count = q.drain(consumer, 4096);
                drained.add(count);
                Thread.yield();
            }
            assertEquals(800_000, drained.sum(), "Iteration: " + x);
        }
    }

    @Test
    void multiProducerNoLossNoDuplication() {
        int producers = 8;
        int perProducer = 50_000;
        int total = producers * perProducer;

        UnboundedMpscQueue<Long> q =
                new UnboundedMpscQueue<>(1024);

        ExecutorService exec = Executors.newFixedThreadPool(producers);

        Set<Long> produced = ConcurrentHashMap.newKeySet();
        Set<Long> consumed = ConcurrentHashMap.newKeySet();

        CountDownLatch latch = new CountDownLatch(producers);

        for (int p = 0; p < producers; p++) {
            final int id = p;
            exec.submit(() -> {
                for (int i = 0; i < perProducer; i++) {
                    long val = (((long) id) << 32) | i;

                    while (!q.offer(val)) {
                        Thread.yield();
                    }

                    produced.add(val);
                }
                latch.countDown();
            });
        }

        Consumer<Long> consumer = (val) -> {
            if (!consumed.add(val)) {
                fail("Duplicate: " + val);
            }
        };

        while(produced.size() < total) {
            Thread.onSpinWait();
        }

        long deadline = System.nanoTime() + TimeUnit.DAYS.toNanos(1);
        while ((latch.getCount() > 0 || consumed.size() < total)
                && System.nanoTime() < deadline) {
            q.drain(consumer, perProducer);
            Thread.onSpinWait();
        }

        exec.shutdownNow();

        assertEquals(produced.size(), consumed.size());
        assertEquals(produced, consumed);
    }
}