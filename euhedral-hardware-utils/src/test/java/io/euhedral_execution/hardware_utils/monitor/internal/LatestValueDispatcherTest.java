package io.euhedral_execution.hardware_utils.monitor.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.monitor.ResourceMonitor.MonitorListener;
import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.HardwareUtilization;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
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

    private static Thread newDispatchThread(Runnable creator) {
        Set<Thread> before = new HashSet<>(Thread.getAllStackTraces().keySet());
        creator.run();
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> !before.contains(t))
                .filter(t -> t.getName().equals("Euhedral-ResourceMonitor-Dispatcher"))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void idleDispatcherDoesNotSpin() throws InterruptedException {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(bean.isThreadCpuTimeSupported());
        LatestValueDispatcher idle = null;
        try {
            LatestValueDispatcher[] holder = new LatestValueDispatcher[1];
            Thread t = newDispatchThread(() -> holder[0] = new LatestValueDispatcher());
            idle = holder[0];
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                while (t.getState() != Thread.State.WAITING
                        && !Thread.currentThread().isInterrupted()) {
                    Thread.yield();
                }
            });
            long before = bean.getThreadCpuTime(t.getId());
            Thread.sleep(200); // lets wall time pass only; no synchronization depends on it
            long after = bean.getThreadCpuTime(t.getId());
            assertTrue(before >= 0 && after >= 0, "dispatch thread terminated");
            assertTrue(after - before < 20_000_000L, "CPU ns burned while idle: " + (after - before));
        } finally {
            if (idle != null) {
                idle.beginClose(null);
                idle.awaitClosed();
            }
        }
    }

    @Test
    void offerAfterIdleIsDeliveredPromptly() throws InterruptedException {
        CountDownLatch delivered = new CountDownLatch(1);
        dispatcher.addListener(u -> delivered.countDown());
        Thread.sleep(100); // let the dispatcher park; correctness does not depend on it
        dispatcher.offer(util1);
        assertTrue(delivered.await(5, TimeUnit.SECONDS));
    }

    @Test
    void strayInterruptDoesNotBreakDispatcher() throws InterruptedException {
        LatestValueDispatcher[] holder = new LatestValueDispatcher[1];
        Thread t = newDispatchThread(() -> holder[0] = new LatestValueDispatcher());
        LatestValueDispatcher d = holder[0];
        try {
            CountDownLatch delivered = new CountDownLatch(1);
            d.addListener(u -> delivered.countDown());
            t.interrupt();
            d.offer(util1);
            assertTrue(delivered.await(5, TimeUnit.SECONDS));
        } finally {
            d.beginClose(null);
            d.awaitClosed();
        }
    }
}
