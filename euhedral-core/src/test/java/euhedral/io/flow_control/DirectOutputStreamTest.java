package euhedral.io.flow_control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import euhedral.io.frames.AbstractFrame;
import euhedral.queues.QueueConsumer;
import euhedral.queues.common.PartitionedQueue;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test_utils.TestFrame;
import test_utils.TestReceiver;

class DirectOutputStreamTest {

    private PartitionedQueue<AbstractFrame> queue;
    private Consumer<AbstractFrame> applyToEach;
    private DirectOutputStream stream;
    private TestReceiver terminal;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        queue = mock(PartitionedQueue.class);
        applyToEach = mock(Consumer.class);

        stream = new DirectOutputStream(queue, applyToEach);

        terminal = new TestReceiver();
    }

    @Test
    void shouldAddDownstreamSuccessfully() {
        stream.addDownstream(terminal);

        assertSame(stream, terminal.upstream);
        assertNull(terminal.error);
    }

    @Test
    void shouldRejectSecondSubscriber() {
        TestReceiver second = new TestReceiver();

        stream.addDownstream(terminal);
        stream.addDownstream(second);

        assertNotNull(second.error);
        assertInstanceOf(IllegalAccessException.class, second.error);
        assertEquals("This class already has a terminal", second.error.getMessage());
    }

    @Test
    void shouldRequestDemand() {
        stream.request(5);

        assertEquals(5, stream.demand.get());
    }

    @Test
    void shouldThrowOnNegativeRequest() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> stream.request(-1)
        );

        assertTrue(ex.getMessage().contains("Cannot pass a negative request"));
    }

    @Test
    void shouldPushFramesToSubscriber() {
        TestFrame frame1 = new TestFrame("a");
        TestFrame frame2 = new TestFrame("b");

        stream.addDownstream(terminal);
        stream.request(2);

        when(queue.drain(any(), eq(2L)))
                .thenAnswer(invocation -> {
                    QueueConsumer<AbstractFrame> consumer = invocation.getArgument(0);

                    consumer.consume(frame1);
                    consumer.consume(frame2);

                    return 2L;
                });

        long drained = stream.push(10);

        assertEquals(2, drained);

        assertEquals(2, terminal.received.size());
        assertEquals("a", terminal.received.get(0).value);
        assertEquals("b", terminal.received.get(1).value);

        verify(applyToEach).accept(frame1);
        verify(applyToEach).accept(frame2);
    }

    @Test
    void shouldRespectPushLimit() {
        stream.addDownstream(terminal);
        stream.request(100);

        when(queue.drain(any(), eq(3L))).thenReturn(3L);

        long drained = stream.push(3);

        assertEquals(3, drained);

        verify(queue).drain(any(), eq(3L));
    }

    @Test
    void shouldNotPushWithoutDemand() {
        stream.addDownstream(terminal);

        long drained = stream.push(10);

        assertEquals(0, drained);

        verify(queue, never()).drain(any(), anyInt());
    }

    @Test
    void shouldNotPushWithoutSubscriber() {
        stream.request(10);

        long drained = stream.push(10);

        assertEquals(0, drained);

        verify(queue, never()).drain(any(), anyInt());
    }

    @Test
    void shouldNotPushWhenCancelled() {
        stream.addDownstream(terminal);
        stream.request(10);

        stream.complete();

        long drained = stream.push(10);

        assertEquals(0, drained);

        verify(queue, never()).drain(any(), anyInt());
    }

    @Test
    void shouldCompleteStream() {
        stream.addDownstream(terminal);

        stream.complete();

        assertTrue(stream.complete);
        assertNull(stream.terminal);
    }

    @Test
    void shouldReturnIsEmptyFromQueue() {
        when(queue.isEmpty()).thenReturn(true);

        assertTrue(stream.isEmpty());

        verify(queue).isEmpty();
    }

    @Test
    void shouldEnableUnlimitedModeOnOverflow() {
        stream.request(Long.MAX_VALUE);
        stream.request(Long.MAX_VALUE);

        assertTrue(stream.unlimited);
    }

    @Test
    void shouldNotDecreaseDemandInUnlimitedMode() {
        stream.addDownstream(terminal);

        stream.request(Long.MAX_VALUE);
        stream.request(Long.MAX_VALUE);

        when(queue.drain(any(), anyInt())).thenReturn(5L);

        long before = stream.demand.get();

        stream.push(10);

        long after = stream.demand.get();

        assertEquals(before, after);
    }

    @Test
    void shouldDecreaseDemandAfterPush() {
        stream.addDownstream(terminal);

        stream.request(10);

        when(queue.drain(any(), eq(5L))).thenReturn(5L);

        stream.push(5);

        assertEquals(5, stream.demand.get());
    }

}