package euhedral.io.flow_control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import euhedral.io.frames.AbstractFrame;
import euhedral.queues.QueueConsumer;
import euhedral.queues.common.PartitionedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test_utils.TestFrame;

class BufferedBridgeTest {

    private PartitionedQueue<AbstractFrame> queue;
    private QueueConsumer<AbstractFrame> drainConsumer;
    private QueueConsumer<AbstractFrame> hookConsumer;

    private BufferedBridge bridge;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        queue = mock(PartitionedQueue.class);
        drainConsumer = mock(QueueConsumer.class);
        hookConsumer = mock(QueueConsumer.class);

        bridge = new BufferedBridge(queue, drainConsumer, hookConsumer);
    }

    @Test
    void shouldDrainUsingMaxIntegerLimit() {
        when(queue.drain(drainConsumer, Integer.MAX_VALUE)).thenReturn(5);

        int drained = bridge.drain();

        assertEquals(5, drained);

        verify(queue).drain(drainConsumer, Integer.MAX_VALUE);
    }

    @Test
    void shouldOfferFrameSuccessfully() {
        TestFrame frame = new TestFrame("frame");

        when(queue.offer(frame)).thenReturn(true);

        boolean result = bridge.offer(frame);

        assertTrue(result);

        verify(queue).offer(frame);
    }

    @Test
    void shouldInvokeHookOnSuccessfulOffer() {
        TestFrame frame = new TestFrame("frame");

        when(queue.offer(frame)).thenReturn(true);

        boolean result = bridge.offer(frame);

        assertTrue(result);

        verify(hookConsumer).consume(frame);
    }

    @Test
    void shouldNotInvokeHookWhenOfferFails() {
        TestFrame frame = new TestFrame("frame");

        when(queue.offer(frame)).thenReturn(false);

        boolean result = bridge.offer(frame);

        assertFalse(result);

        verify(hookConsumer, never()).consume(any());
    }

    @Test
    void shouldAllowNullHook() {
        BufferedBridge noHookBridge = new BufferedBridge(queue, drainConsumer);

        TestFrame frame = new TestFrame("frame");

        when(queue.offer(frame)).thenReturn(true);

        boolean result = noHookBridge.offer(frame);

        assertTrue(result);

        verify(queue).offer(frame);
    }

    @Test
    void shouldDrainZeroElements() {
        when(queue.drain(drainConsumer, Integer.MAX_VALUE)).thenReturn(0);

        int drained = bridge.drain();

        assertEquals(0, drained);

        verify(queue).drain(drainConsumer, Integer.MAX_VALUE);
    }

    @Test
    void shouldOfferMultipleFrames() {
        TestFrame frame1 = new TestFrame("a");
        TestFrame frame2 = new TestFrame("b");

        when(queue.offer(any())).thenReturn(true);

        assertTrue(bridge.offer(frame1));
        assertTrue(bridge.offer(frame2));

        verify(queue).offer(frame1);
        verify(queue).offer(frame2);

        verify(hookConsumer).consume(frame1);
        verify(hookConsumer).consume(frame2);
    }
}