package io.euhedral_execution.hardware_utils.compatibility;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.ThreadTools;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class PinnedThreadExecutorCompatibilityTest {

    @Test
    void submissionsUseConcurrentFreshThreads() throws Exception {
        int cpu = ThreadTools.BASE_MASK.nextSetBit(0);
        assertTrue(cpu >= 0, "no CPU is available for the test");
        ConcurrentLinkedQueue<Thread> created = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        PinnedThreadExecutor executor = PinnedThreadExecutor.getOrSetIfAbsent(runnable -> {
            Thread thread = new Thread(runnable);
            created.add(thread);
            return thread;
        }, cpu, "p0-concurrent-fresh-thread", Thread.NORM_PRIORITY, true);
        try {
            Runnable task = () -> {
                try {
                    assertTrue(start.await(5, SECONDS));
                    entered.countDown();
                    assertTrue(release.await(5, SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            };
            executor.execute(task);
            executor.execute(task);
            start.countDown();
            assertTrue(entered.await(5, SECONDS),
                    "both submissions must enter before either is released");
            assertEquals(2, created.size());
            Thread first = created.poll();
            Thread second = created.poll();
            assertNotSame(first, second);
        } finally {
            release.countDown();
            executor.close();
            assertTrue(executor.awaitTermination(5, SECONDS));
        }
        assertNull(PinnedThreadExecutor.get(cpu));
    }
}
