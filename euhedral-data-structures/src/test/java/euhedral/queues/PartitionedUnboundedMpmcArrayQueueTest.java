package euhedral.queues;

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

import org.junit.jupiter.api.Test;

class PartitionedUnboundedMpmcArrayQueueTest {

    @Test
    void singleThreadOfferDrain() {
        int chunkSize = 128;
        PartitionedUnboundedMpmcArrayQueue<Integer> q =
                new PartitionedUnboundedMpmcArrayQueue<>(4, chunkSize, 2);

        for (int i = 1; i <= chunkSize * 4; i++) {
            assertTrue(q.offer(0, i));
        }

        final int[] drained = new int[] {0};
        QueueConsumer<Integer> consumer = (val) -> {
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
        PartitionedUnboundedMpmcArrayQueue<Long> q =
                new PartitionedUnboundedMpmcArrayQueue<>(partitions, 4096, 4);
        int batch = 100_000;

        QueueConsumer<Long> consumer = (val) -> {
        };
        ExecutorService exec = Executors.newFixedThreadPool(16);
        for (int x = 0; x < 50; x++) {
            CountDownLatch end = new CountDownLatch(8);

            LongAdder offered = new LongAdder();
            LongAdder drained = new LongAdder();
            for (int i = 0; i < 8; i++) {
                exec.submit(() -> {
                    try {
                        for (int j = 0; j < batch; j++) {
                            long v = ThreadLocalRandom.current().nextLong();

                            while (!q.offer(v, System.nanoTime())) {
                                Thread.onSpinWait();
                            }
                            offered.increment();
                        }
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                });
            }

            for (int i = 0; i < 8; i++) {
                exec.submit(() -> {
                    while (drained.sum() < batch * 8) {
                        int count = q.drain(consumer, 4096);
                        drained.add(count);
                        Thread.yield();
                    }
                    end.countDown();
                });
            }
            end.await(5, TimeUnit.SECONDS);

            assertEquals(800_000, drained.sum(),
                    String.format("Iteration: %d Consumed: %d Offered: %d\n%s", x, drained.sum(),
                            offered.sum(), q));
        }
    }

    @Test
    void multiProducerNoLossNoDuplication() {
        int producers = 8;
        int perProducer = 50_000;
        int total = producers * perProducer;

        PartitionedUnboundedMpmcArrayQueue<Long> q =
                new PartitionedUnboundedMpmcArrayQueue<>(8, 1024, 4);

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
        while ((latch.getCount() > 0 || consumed.size() < total) && System.nanoTime() < deadline) {
            q.drain(consumer, perProducer);
        }

        exec.shutdownNow();

        assertEquals(produced.size(), consumed.size(), q.toString());
        assertEquals(produced, consumed, q.toString());
    }

    @Test
    void multiConsumerNoLossNoDuplication() throws Exception {
        int producers = 4;
        int consumers = 4;
        int perProducer = 50_000;

        PartitionedUnboundedMpmcArrayQueue<Long> q =
                new PartitionedUnboundedMpmcArrayQueue<>(4, 512, 4);

        ExecutorService exec = Executors.newFixedThreadPool(producers + consumers);

        Set<Long> consumed = ConcurrentHashMap.newKeySet();

        CountDownLatch prodLatch = new CountDownLatch(producers);

        for (int p = 0; p < producers; p++) {
            final int id = p;
            exec.submit(() -> {
                for (int i = 0; i < perProducer; i++) {
                    long val = (((long) id) << 32) | i;

                    while (!q.offer(id % 4, val)) {
                        Thread.yield();
                    }
                }
                prodLatch.countDown();
            });
        }

        CountDownLatch consLatch = new CountDownLatch(consumers);
        QueueConsumer<Long> consumer = (val) -> {
            if (!consumed.add(val)) {
                fail("Duplicate detected: " + val);
            }
        };

        for (int c = 0; c < consumers; c++) {
            exec.submit(() -> {
                while (prodLatch.getCount() > 0 || consumed.size() < perProducer * producers) {
                    q.drain(consumer, 512);
                }
                consLatch.countDown();
            });
        }

        consLatch.await(5, TimeUnit.SECONDS);
        exec.shutdownNow();

        assertEquals(producers * perProducer, consumed.size(), q.toString());
    }
}