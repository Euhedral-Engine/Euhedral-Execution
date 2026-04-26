package euhedral.queues;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class PartitionedMpmcArrayQueueTest {
    @Test
    void singleThreadOfferDrain() {
        PartitionedMpmcArrayQueue<Integer> q =
                new PartitionedMpmcArrayQueue<>(4, 128, false);

        for (int i = 0; i < 1000; i++) {
            if(i < 128) {
                assertTrue(q.offer(0, i));
            } else {
                assertFalse(q.offer(0, i));
            }
        }

        Integer[] buf = new Integer[1000];
        int drained = q.drain(0, buf, 0, 1000);

        assertEquals(128, drained);

        for (int i = 0; i < drained; i++) {
            assertEquals(i, buf[i]);
        }
    }

    @Test
    void multiProducerNoLossNoDuplication() {
        int producers = 8;
        int perProducer = 50_000;
        int total = producers * perProducer;

        PartitionedMpmcArrayQueue<Long> q =
                new PartitionedMpmcArrayQueue<>(8, 1024, false);

        try(ExecutorService exec = Executors.newFixedThreadPool(producers)) {

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

            Long[] buf = new Long[1024];

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while ((latch.getCount() > 0 || consumed.size() < total)
                    && System.nanoTime() < deadline) {
                int drained = q.drain(buf, 0, buf.length);
                for (int i = 0; i < drained; i++) {
                    if (!consumed.add(buf[i])) {
                        fail("Duplicate: " + buf[i]);
                    }
                }
            }

            exec.shutdownNow();

            assertEquals(produced.size(), consumed.size());
            assertEquals(produced, consumed);
        }
    }

    @Test
    void multiConsumerNoDuplication() throws Exception {
        int producers = 4;
        int consumers = 4;
        int perProducer = 50_000;

        PartitionedMpmcArrayQueue<Long> q =
                new PartitionedMpmcArrayQueue<>(4, 512, false);

        try(ExecutorService exec = Executors.newFixedThreadPool(producers + consumers)) {

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

            for (int c = 0; c < consumers; c++) {
                exec.submit(() -> {
                    Long[] buf = new Long[256];

                    while (prodLatch.getCount() > 0 || q.getSize(0) > 0) {
                        int drained = q.drain(buf, 0, buf.length);

                        for (int i = 0; i < drained; i++) {
                            if (!consumed.add(buf[i])) {
                                fail("Duplicate detected: " + buf[i]);
                            }
                        }
                    }
                    consLatch.countDown();
                });
            }

            consLatch.await(1, TimeUnit.SECONDS);
            exec.shutdownNow();

            assertEquals(producers * perProducer, consumed.size());
        }
    }

    @Test
    void retireAndDrainCompletes() {
        PartitionedMpmcArrayQueue<Integer> q =
                new PartitionedMpmcArrayQueue<>(2, 64, true);

        int inserted = 0;

        for (int i = 0; i < 1000; i++) {
            if (q.offer(0, i)) {
                inserted++;
            }
        }

        while (q.offer(0, 9999)) {
            inserted++;
        }

        Integer[] buf = new Integer[128];

        int total = 0;
        while (!q.isDrained()) {
            total += q.drain(buf, 0, buf.length);
        }

        assertEquals(inserted, total);
    }
}