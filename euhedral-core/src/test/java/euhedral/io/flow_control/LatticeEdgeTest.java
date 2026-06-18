package euhedral.io.flow_control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import euhedral.io.flow_control.UpstreamQueue.UpstreamHandle;
import euhedral.io.utils.QueueConsumer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test_utils.TestFrame;
import test_utils.TestReceiver;

class LatticeEdgeTest {

    private AtomicBoolean drain;
    private LatticeEdge edge;

    @BeforeEach
    void setup() {
        UpstreamQueue.UP_QUEUE.remove();

        drain = new AtomicBoolean(false);
        edge = new LatticeEdge(drain);
    }

    @AfterEach
    void cleanup() {
        UpstreamQueue.UP_QUEUE.remove();
    }

    @Test
    void shouldCreateThreadQueueOnRegister() {
        edge.register();

        assertEquals(1, edge.getThreadCount());
    }

    @Test
    void shouldReuseThreadQueue() {
        UpstreamQueue first = edge.getThreadUpstreamQueue();
        UpstreamQueue second = edge.getThreadUpstreamQueue();

        assertSame(first, second);
        assertEquals(1, edge.getThreadCount());
    }

    @Test
    void shouldReturnSingleLayerWidthWithoutSibling() {
        assertEquals(1, edge.getLayerWidth());
    }

    @Test
    void shouldCalculateLayerWidthAcrossSiblings() {
        LatticeEdge second = new LatticeEdge(new AtomicBoolean());
        LatticeEdge third = new LatticeEdge(new AtomicBoolean());

        edge.setSibling(second);
        second.setSibling(third);
        third.setSibling(edge);

        assertEquals(3, edge.getLayerWidth());
        assertEquals(3, second.getLayerWidth());
        assertEquals(3, third.getLayerWidth());
    }

    @Test
    void shouldForwardPushToDownstream() {
        TestReceiver terminal = new TestReceiver();

        edge.addDownstream(terminal);

        TestFrame frame = new TestFrame("hello");

        edge.push(frame);

        assertEquals(1, terminal.received.size());
        assertSame(frame, terminal.received.get(0));
    }

    @Test
    void shouldForwardErrorsToDownstream() {
        TestReceiver terminal = new TestReceiver();

        edge.addDownstream(terminal);

        RuntimeException error = new RuntimeException("boom");

        edge.onError(error);

        assertSame(error, terminal.error);
    }

    @Test
    void shouldCloseAndCompleteDownstream() {
        TestReceiver terminal = new TestReceiver();

        edge.addDownstream(terminal);

        edge.close();

        assertTrue(terminal.completed);
        assertNull(edge.downstream);
    }

    @Test
    void shouldOnlyCloseOnce() {
        TestReceiver terminal = new TestReceiver();

        edge.addDownstream(terminal);

        edge.close();
        edge.close();

        assertTrue(terminal.completed);
    }

    @Test
    void shouldIgnoreNegativeRequest() {
        edge.request(-1);

        assertEquals(0, edge.getThreadCount());
    }

    @Test
    void shouldIgnoreRequestWhenDrainActive() {
        drain.set(true);

        edge.request(10);

        assertEquals(0, edge.getThreadCount());
    }

    @Test
    void shouldIgnorePullWhenDrainActive() {
        drain.set(true);

        QueueConsumer buffer = mock(QueueConsumer.class);

        assertDoesNotThrow(() -> edge.pull(buffer, 10));
    }

    @Test
    void shouldSetParent() {
        LatticeEdge parent = new LatticeEdge(new AtomicBoolean());

        edge.setParent(parent);

        assertEquals(parent, LatticeEdge.PARENT.getOpaque(edge));
    }

    @Test
    void shouldClearParentWhenNullPassed() {
        LatticeEdge parent = new LatticeEdge(new AtomicBoolean());

        edge.setParent(parent);

        edge.setParent(null);

        assertNull(LatticeEdge.PARENT.getOpaque(edge));
    }

    @Test
    void shouldDelegateRequestToParent() {
        LatticeEdge parent = spy(new LatticeEdge(new AtomicBoolean()));

        edge.setParent(parent);

        edge.request(42);

        verify(parent).request(42);
    }

    @Test
    void shouldDelegatePullToParent() {
        LatticeEdge parent = spy(new LatticeEdge(new AtomicBoolean()));

        edge.setParent(parent);

        QueueConsumer buffer = mock(QueueConsumer.class);

        edge.pull(buffer, 123);

        verify(parent).pull(buffer, 123);
    }

    @Test
    void shouldAddTerminalDownstream() {
        TestReceiver terminal = new TestReceiver();

        edge.addDownstream(terminal);

        assertSame(terminal, edge.downstream);
    }

    @Test
    void shouldRejectSecondTerminalDownstream() {
        TestReceiver first = new TestReceiver();
        TestReceiver second = new TestReceiver();

        edge.addDownstream(first);
        edge.addDownstream(second);

        assertNotNull(second.error);
        assertInstanceOf(IllegalStateException.class, second.error);
        assertEquals(
                "Already added as an upstream by a terminal downstream",
                second.error.getMessage()
        );
    }

    @Test
    void shouldChainRecursiveDownstreamEdges() {
        LatticeEdge downstream = spy(new LatticeEdge(new AtomicBoolean()));

        edge.addDownstream(downstream);

        verify(downstream).setParent(edge);
    }

    @Test
    void shouldForwardDownstreamToExistingRecursiveEdge() {
        LatticeEdge existing = spy(new LatticeEdge(new AtomicBoolean()));
        LatticeEdge next = mock(LatticeEdge.class);

        edge.addDownstream(existing);
        edge.addDownstream(next);

        verify(existing).addDownstream(next);
    }

    @Test
    void shouldAddUpstreamHandle() {
        UpstreamHandle upstream = mock(UpstreamHandle.class);

        when(upstream.isComplete()).thenReturn(false);

        edge.addUpstream(upstream);

        assertEquals(1, edge.getUpstreamCount());
    }

    @Test
    void shouldIgnoreCompleteUpstreamsDuringRegistration() {
        UpstreamHandle upstream = mock(UpstreamHandle.class);

        when(upstream.isComplete()).thenReturn(true);

        edge.addUpstream(upstream);

        edge.register();

        assertEquals(1, edge.getThreadCount());
    }

    @Test
    void shouldTransferToParent() {
        UpstreamHandle upstream = mock(UpstreamHandle.class);

        when(upstream.isComplete()).thenReturn(false);

        edge.addUpstream(upstream);

        edge.register();

        LatticeEdge parent = new LatticeEdge(new AtomicBoolean());

        edge.setParent(parent);

        assertTrue(parent.getUpstreamCount() > 0);
        assertTrue(parent.getThreadCount() > 0);
    }

    @Test
    void shouldRemoveThread() {
        edge.register();

        assertEquals(1, edge.getThreadCount());

        edge.removeThread(Thread.currentThread());

        assertEquals(0, edge.getThreadCount());
    }

    @Test
    void shouldIgnoreNullThreadRemoval() {
        assertDoesNotThrow(() -> edge.removeThread(null));
    }

    @Test
    void shouldDelegateThreadRemovalToParent() {
        LatticeEdge parent = spy(new LatticeEdge(new AtomicBoolean()));

        edge.setParent(parent);

        Thread thread = Thread.currentThread();

        edge.removeThread(thread);

        verify(parent).removeThread(thread);
    }

    @Test
    void shouldAlwaysReportIncomplete() {
        assertFalse(edge.isComplete());
    }
}