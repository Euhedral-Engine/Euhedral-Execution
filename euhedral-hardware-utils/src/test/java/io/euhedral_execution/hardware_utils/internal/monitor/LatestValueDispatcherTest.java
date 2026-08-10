package io.euhedral_execution.hardware_utils.internal.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.hardware_utils.ResourceMonitor.MonitorListener;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
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

        dispatcher.beginClose(() -> {
            hookCalls.incrementAndGet();
            hookLatch.countDown();
        });

        assertTrue(hookLatch.await(5, TimeUnit.SECONDS));
        dispatcher.awaitClosed();
        dispatcher.awaitClosed(); // Reentrant close barrier check

        assertEquals(1, hookCalls.get());
    }
}
