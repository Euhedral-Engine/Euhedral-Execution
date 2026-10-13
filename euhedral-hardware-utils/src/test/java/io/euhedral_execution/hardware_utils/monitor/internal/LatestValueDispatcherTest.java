package io.euhedral_execution.hardware_utils.monitor.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.monitor.ResourceMonitor.MonitorListener;
import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.HardwareUtilization;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LatestValueDispatcherTest {

    private LatestValueDispatcher dispatcher;
    private HardwareUtilization util1;
    private HardwareUtilization util2;

    @BeforeEach
    void setUp() {
        dispatcher = new LatestValueDispatcher();
        util1 = org.mockito.Mockito.mock(HardwareUtilization.class);
        util2 = org.mockito.Mockito.mock(HardwareUtilization.class);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        dispatcher.beginClose(null);
        dispatcher.awaitClosed();
    }

    @Test
    void testIdentityDedupe() throws InterruptedException {
        AtomicInteger calls = new AtomicInteger();
        MonitorListener listener = util -> calls.incrementAndGet();

        dispatcher.addListener(listener);
        dispatcher.addListener(listener); // Should deduplicate

        CountDownLatch latch = new CountDownLatch(1);
        dispatcher.addListener(util -> latch.countDown());

        dispatcher.offer(util1);
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        assertEquals(1, calls.get());
    }

    @Test
    void testCoalescingAndNonBlocking() throws InterruptedException {
        CountDownLatch blockLatch = new CountDownLatch(1);
        CountDownLatch calledLatch = new CountDownLatch(1);
        AtomicReference<HardwareUtilization> received = new AtomicReference<>();

        MonitorListener blockingListener = util -> {
            calledLatch.countDown();
            try {
                blockLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            received.set(util);
        };

        dispatcher.addListener(blockingListener);
        dispatcher.offer(util1);

        // Wait for listener to block
        assertTrue(calledLatch.await(5, TimeUnit.SECONDS));

        // Offer multiple while blocked; they should coalesce without blocking caller
        dispatcher.offer(util1);
        dispatcher.offer(util2); // This one should be delivered next

        CountDownLatch secondCallLatch = new CountDownLatch(1);
        dispatcher.addListener(util -> secondCallLatch.countDown());

        // Release the first listener
        blockLatch.countDown();

        // Wait for the next delivery
        assertTrue(secondCallLatch.await(5, TimeUnit.SECONDS));

        assertEquals(util2, received.get());
    }

    @Test
    void testCallbackTimeAddAndClose() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean closedInCallback = new AtomicBoolean(false);

        MonitorListener listener = new MonitorListener() {
            @Override
            public void update(HardwareUtilization util) {
                // Reentrant call
                dispatcher.addListener(u -> {});
                dispatcher.beginClose(() -> closedInCallback.set(true));
                latch.countDown();
            }
        };

        dispatcher.addListener(listener);
        dispatcher.offer(util1);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        dispatcher.awaitClosed();
        assertTrue(closedInCallback.get());
    }

    @Test
    void testExceptionIsolation() throws InterruptedException {
        CountDownLatch secondListenerLatch = new CountDownLatch(1);

        dispatcher.addListener(util -> {
            throw new RuntimeException("Failing listener");
        });

        dispatcher.addListener(util -> {
            throw new Error("Failing error listener");
        });

        dispatcher.addListener(util -> secondListenerLatch.countDown());

        dispatcher.offer(util1);

        // Third listener must still be called
        assertTrue(secondListenerLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void testExactlyOnceUnlockedTerminationNotification() throws InterruptedException {
        AtomicInteger hookCalls = new AtomicInteger();
        CountDownLatch hookLatch = new CountDownLatch(1);
        MonitorListener listener = util -> {};
        dispatcher.addListener(listener);

        dispatcher.beginClose(() -> {
            dispatcher.removeListener(listener);
            hookCalls.incrementAndGet();
            hookLatch.countDown();
        });
        dispatcher.beginClose(() -> hookCalls.addAndGet(100));

        assertTrue(hookLatch.await(5, TimeUnit.SECONDS));
        dispatcher.awaitClosed();
        dispatcher.awaitClosed(); // Reentrant close barrier check

        assertEquals(1, hookCalls.get());
    }

    /// Captures the dispatch thread deterministically via a listener callback, then waits until idle.
    private Thread captureIdleDispatchThread(LatestValueDispatcher d) throws InterruptedException {
        AtomicReference<Thread> captured = new AtomicReference<>();
        CountDownLatch seen = new CountDownLatch(1);
        d.addListener(u -> {
            captured.compareAndSet(null, Thread.currentThread());
            seen.countDown();
        });
        d.offer(util1);
        assertTrue(seen.await(5, TimeUnit.SECONDS));
        Thread t = captured.get();
        awaitWaiting(t);
        return t;
    }

    private static void awaitWaiting(Thread t) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (t.getState() != Thread.State.WAITING
                    && !Thread.currentThread().isInterrupted()) {
                Thread.yield();
            }
        });
    }

    private static long cpuNanosOver200ms(Thread t) throws InterruptedException {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        long before = bean.getThreadCpuTime(t.getId());
        Thread.sleep(200); // lets wall time pass only; no synchronization depends on it
        long after = bean.getThreadCpuTime(t.getId());
        assertTrue(before >= 0 && after >= 0, "dispatch thread terminated");
        return after - before;
    }

    @Test
    void idleDispatcherDoesNotSpin() throws InterruptedException {
        Assumptions.assumeTrue(ManagementFactory.getThreadMXBean().isThreadCpuTimeSupported());
        Thread t = captureIdleDispatchThread(dispatcher);
        long burned = cpuNanosOver200ms(t);
        assertTrue(burned < 20_000_000L, "CPU ns burned while idle: " + burned);
    }

    @Test
    void offerAfterIdleIsDeliveredPromptly() throws InterruptedException {
        captureIdleDispatchThread(dispatcher);
        CountDownLatch delivered = new CountDownLatch(1);
        dispatcher.addListener(u -> delivered.countDown());
        dispatcher.offer(util2);
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
    }

    @Test
    void strayInterruptDoesNotSpinOrBreakDispatcher() throws InterruptedException {
        Assumptions.assumeTrue(ManagementFactory.getThreadMXBean().isThreadCpuTimeSupported());
        Thread t = captureIdleDispatchThread(dispatcher);
        t.interrupt();
        awaitWaiting(t);
        long burned = cpuNanosOver200ms(t);
        assertTrue(burned < 20_000_000L, "CPU ns burned after stray interrupt: " + burned);
        assertTrue(!t.isInterrupted(), "interrupt flag must be cleared");
        CountDownLatch delivered = new CountDownLatch(1);
        dispatcher.addListener(u -> delivered.countDown());
        dispatcher.offer(util2);
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
    }
}
