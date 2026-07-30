package io.euhedral_execution.core.utils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FlowThreadTest {

    @Test
    void shouldInitializeContextOnARegularThread() throws Exception {
        AtomicReference<FlowThread.FlowContext> initialized = new AtomicReference<>();
        AtomicReference<FlowThread.FlowContext> retrieved = new AtomicReference<>();
        AtomicReference<FlowThread.FlowContext> cleared = new AtomicReference<>();

        Thread thread = new Thread(() -> {
            initialized.set(FlowThread.initializeContext());
            retrieved.set(FlowThread.getContext());
            FlowThread.clearContext();
            cleared.set(FlowThread.getContext());
        });

        thread.start();
        thread.join();

        assertNotNull(initialized.get());
        assertSame(initialized.get(), retrieved.get());
        assertNull(cleared.get());
    }
}
