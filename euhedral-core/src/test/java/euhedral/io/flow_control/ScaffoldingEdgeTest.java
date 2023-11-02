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
import euhedral.io.utils.DrainBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test_utils.TestFrame;
import test_utils.TestTerminal;

class ScaffoldingEdgeTest {

    private AtomicBoolean drain;
    private ScaffoldingEdge edge;

    @BeforeEach
    void setup() {
        UpstreamQueue.UP_QUEUE.remove();

        drain = new AtomicBoolean(false);
        edge = new ScaffoldingEdge(drain);
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
        ScaffoldingEdge second = new ScaffoldingEdge(new AtomicBoolean());
        ScaffoldingEdge third = new ScaffoldingEdge(new AtomicBoolean());

        edge.setSibling(second);
        second.setSibling(third);
        third.setSibling(edge);

        assertEquals(3, edge.getLayerWidth());
        assertEquals(3, second.getLayerWidth());
        assertEquals(3, third.getLayerWidth());
    }

    @Test
    void shouldForwardOnNextToDownstream() {
        TestTerminal terminal = new TestTerminal();

        edge.addDownstream(terminal);

        TestFrame frame = new TestFrame("hello");

        edge.onNext(frame);

        assertEquals(1, terminal.received.size());
        assertSame(frame, terminal.received.get(0));
    }

    @Test
    void shouldForwardErrorsToDownstream() {
        TestTerminal terminal = new TestTerminal();

        edge.addDownstream(terminal);

        RuntimeException error = new RuntimeException("boom");

        edge.onError(error);

        assertSame(error, terminal.error);
    }

    @Test
    void shouldCloseAndCompleteDownstream() {
        TestTerminal terminal = new TestTerminal();

        edge.addDownstream(terminal);

        edge.close();

        assertTrue(terminal.completed);
        assertNull(edge.downstream);
    }

    @Test
    void shouldOnlyCloseOnce() {
        TestTerminal terminal = new TestTerminal();

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

        DrainBuffer buffer = mock(DrainBuffer.class);

        assertDoesNotThrow(() -> edge.pull(buffer, 10));
    }

    @Test
    void shouldSetParent() {
        ScaffoldingEdge parent = new ScaffoldingEdge(new AtomicBoolean());

        edge.setParent(parent);

        assertEquals(parent, ScaffoldingEdge.PARENT.getOpaque(edge));
    }

    @Test
    void shouldClearParentWhenNullPassed() {
        ScaffoldingEdge parent = new ScaffoldingEdge(new AtomicBoolean());

        edge.setParent(parent);

        edge.setParent(null);

        assertNull(ScaffoldingEdge.PARENT.getOpaque(edge));
    }

    @Test
    void shouldDelegateRequestToParent() {
        ScaffoldingEdge parent = spy(new ScaffoldingEdge(new AtomicBoolean()));

        edge.setParent(parent);

        edge.request(42);

        verify(parent).request(42);
    }

    @Test
    void shouldDelegatePullToParent() {
        ScaffoldingEdge parent = spy(new ScaffoldingEdge(new AtomicBoolean()));

        edge.setParent(parent);

        DrainBuffer buffer = mock(DrainBuffer.class);

        edge.pull(buffer, 123);

        verify(parent).pull(buffer, 123);
    }

    @Test
    void shouldAddTerminalDownstream() {
        TestTerminal terminal = new TestTerminal();

        edge.addDownstream(terminal);

        assertSame(terminal, edge.downstream);
    }

    @Test
    void shouldRejectSecondTerminalDownstream() {
        TestTerminal first = new TestTerminal();
        TestTerminal second = new TestTerminal();

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
        ScaffoldingEdge downstream = spy(new ScaffoldingEdge(new AtomicBoolean()));

        edge.addDownstream(downstream);

        verify(downstream).setParent(edge);
    }

    @Test
    void shouldForwardDownstreamToExistingRecursiveEdge() {
        ScaffoldingEdge existing = spy(new ScaffoldingEdge(new AtomicBoolean()));
        ScaffoldingEdge next = mock(ScaffoldingEdge.class);

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

        ScaffoldingEdge parent = new ScaffoldingEdge(new AtomicBoolean());

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
        ScaffoldingEdge parent = spy(new ScaffoldingEdge(new AtomicBoolean()));

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