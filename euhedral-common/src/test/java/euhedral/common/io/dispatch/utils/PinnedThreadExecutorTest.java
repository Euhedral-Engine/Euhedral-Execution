package euhedral.common.io.dispatch.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.openhft.affinity.Affinity;
import org.junit.jupiter.api.Test;

class PinnedThreadExecutorTest {
    @Test
    void testVirtualThreadAffinity() throws Exception {
        int targetCpu = 1; // Assume CPU 1 for test
        PinnedThreadExecutor executor = PinnedThreadExecutor.getOrSetIfAbsent(targetCpu, "Core-1-Worker", 5, true);

        CompletableFuture<Integer> cpuCheck = new CompletableFuture<>();

        // Create and start the reflective VThread
        Thread vt = executor.vThread(() -> {
            // Check the current affinity of the thread
            cpuCheck.complete(Affinity.getCpu());
        });
        vt.start();

        int actualCpu = cpuCheck.get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(targetCpu, actualCpu, "Virtual Thread must be pinned to the specified core");
    }

    @Test
    void testDaemonAndPriority() throws Exception {
        PinnedThreadExecutor executor = PinnedThreadExecutor.getOrSetIfAbsent(0, "Priority-Test", 8, true);

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

    @Test
    void testCarrierAndVThreadAlignment() throws Exception {
        PinnedThreadExecutor core2 = PinnedThreadExecutor.getOrSetIfAbsent(2, "Core2-Executor", 10, true);

        CompletableFuture<Integer> carrierCpu = new CompletableFuture<>();
        CompletableFuture<Integer> vThreadCpu = new CompletableFuture<>();

        core2.execute(() -> {
            carrierCpu.complete(Affinity.getCpu());

            Thread vt = core2.vThread(() -> vThreadCpu.complete(Affinity.getCpu()));
            vt.start();
        });

        assertEquals(2, carrierCpu.get(1, TimeUnit.SECONDS));
        assertEquals(2, vThreadCpu.get(1, TimeUnit.SECONDS));
    }
}