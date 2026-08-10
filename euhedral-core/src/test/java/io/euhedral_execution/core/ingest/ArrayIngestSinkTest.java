package io.euhedral_execution.core.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test_utils.TestFrame;
import test_utils.TestReceiver;

class ArrayIngestSinkTest {

    private TestFrame[] frames;
    private ArrayIngestSink sink;
    private ArrayIngestSink.Delegate delegate;
    private TestReceiver terminal;

    @BeforeEach
    void setup() {
        frames = new TestFrame[] {new TestFrame("a"), new TestFrame("b"), new TestFrame("c")};

        sink = new ArrayIngestSink(frames);
        delegate = (ArrayIngestSink.Delegate) sink.getDelegate();

        terminal = new TestReceiver();
    }

    @Test
    void shouldDeliverRequestedFrames() {
        delegate.addDownstream(terminal);

        delegate.request(2);

        assertEquals(2, terminal.received.size());
        assertEquals("a", terminal.received.get(0).value);
        assertEquals("b", terminal.received.get(1).value);

        assertFalse(terminal.completed);
    }

    @Test
    void shouldCompleteWhenAllFramesConsumed() {
        delegate.addDownstream(terminal);

        delegate.request(10);

        assertEquals(3, terminal.received.size());
        assertTrue(terminal.completed);
    }

    @Test
    void shouldIgnoreZeroDemand() {
        delegate.addDownstream(terminal);

        delegate.request(0);

        assertTrue(terminal.received.isEmpty());
        assertFalse(terminal.completed);
    }

    @Test
    void shouldIgnoreNegativeDemand() {
        delegate.addDownstream(terminal);

        delegate.request(-1);

        assertTrue(terminal.received.isEmpty());
        assertFalse(terminal.completed);
    }

    @Test
    void shouldOnlyAllowSingleSubscriber() {
        TestReceiver second = new TestReceiver();

        delegate.addDownstream(terminal);
        delegate.addDownstream(second);

        assertNotNull(second.error);
        assertInstanceOf(IllegalStateException.class, second.error);
        assertEquals("Already has a downstream", second.error.getMessage());
    }

    @Test
    void shouldCancelAndComplete() {
        delegate.addDownstream(terminal);

        delegate.complete();

        assertTrue(terminal.completed);
    }

    @Test
    void shouldHandleDemandOverflow() {
        delegate.addDownstream(terminal);

        delegate.request(Long.MAX_VALUE);
        delegate.request(Long.MAX_VALUE);

        assertEquals(3, terminal.received.size());
        assertTrue(terminal.completed);
    }
}
