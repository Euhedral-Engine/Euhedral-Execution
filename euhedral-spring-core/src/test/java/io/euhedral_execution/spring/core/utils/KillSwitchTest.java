package io.euhedral_execution.spring.core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class KillSwitchTest {

    @Test
    void boopCompletesReactiveAndRunnableListeners() {
        KillSwitch killSwitch = new KillSwitch();
        AtomicBoolean reactiveComplete = new AtomicBoolean();
        AtomicInteger callbacks = new AtomicInteger();
        killSwitch.addGoner().subscribe(ignored -> {
        }, ignored -> {
        }, () -> reactiveComplete.set(true));
        killSwitch.addGoner(callbacks::incrementAndGet);

        killSwitch.boop();

        assertTrue(killSwitch.isBooped());
        assertTrue(reactiveComplete.get());
        assertEquals(1, callbacks.get());
    }

    @Test
    void listenerAddedAfterBoopRunsImmediately() {
        KillSwitch killSwitch = new KillSwitch();
        AtomicInteger callbacks = new AtomicInteger();
        killSwitch.boop();

        killSwitch.addGoner(callbacks::incrementAndGet);

        assertEquals(1, callbacks.get());
    }

    @Test
    void oneFailingListenerDoesNotPreventRemainingShutdownWork() {
        KillSwitch killSwitch = new KillSwitch();
        AtomicInteger callbacks = new AtomicInteger();
        killSwitch.addGoner(() -> {
            throw new IllegalStateException("failure");
        });
        killSwitch.addGoner(callbacks::incrementAndGet);

        killSwitch.boop();

        assertEquals(1, callbacks.get());
    }
}
