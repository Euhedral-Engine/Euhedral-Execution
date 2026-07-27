package io.euhedral_execution.hardware_utils;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

class PinnedThreadExecutorTest {

    @Test
    void reusesTheCpuExecutorAndAppliesThreadProperties() throws Exception {
        int cpu = ThreadTools.BASE_MASK.nextSetBit(0);
        assertTrue(cpu >= 0, "no CPU is available for the test");

        PinnedThreadExecutor executor = PinnedThreadExecutor.getOrSetIfAbsent(
                Thread::new, cpu, "pinned-unit-test", 42, true);
        try (executor) {
            assertSame(executor, PinnedThreadExecutor.getOrSetIfAbsent(
                    Thread::new, cpu, "ignored", Thread.MIN_PRIORITY, false));

            CompletableFuture<Thread> executedBy = new CompletableFuture<>();
            executor.execute(() -> executedBy.complete(Thread.currentThread()));

            Thread thread = executedBy.get(5, SECONDS);
            assertEquals("pinned-unit-test", thread.getName());
            assertEquals(Thread.MAX_PRIORITY, thread.getPriority());
            assertTrue(thread.isDaemon());
            assertEquals(cpu, executor.getCpu());
        }

        assertTrue(executor.isShutdown());
        assertTrue(executor.awaitTermination(5, SECONDS));
        assertNull(PinnedThreadExecutor.get(cpu));
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {
        }));
    }
}
