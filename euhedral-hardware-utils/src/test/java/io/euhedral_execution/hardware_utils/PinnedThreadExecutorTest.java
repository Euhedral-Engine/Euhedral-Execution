package io.euhedral_execution.hardware_utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

class PinnedThreadExecutorTest {

    @Test
    void testDaemonAndPriority() throws Exception {
        PinnedThreadExecutor
                executor = PinnedThreadExecutor.getOrSetIfAbsent(2, "Priority-Test", 8, true);

        CompletableFuture<Boolean> isDaemon = new CompletableFuture<>();
        CompletableFuture<Integer> priority = new CompletableFuture<>();

        executor.execute(() -> {
            Thread t = Thread.currentThread();
            isDaemon.complete(t.isDaemon());
            priority.complete(t.getPriority());
        });

        assertTrue(isDaemon.get());
        assertEquals(8, priority.get());
    }

}