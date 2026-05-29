package euhedral.io.flow_control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import euhedral.io.generics.ScaffoldingSource;
import euhedral.io.utils.DrainBuffer;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test_utils.TestFrame;
import test_utils.TestTerminal;

class ScaffoldingNodeTest {

    private ScaffoldingNode node;

    @BeforeEach
    void setup() {
        UpstreamQueue.UP_QUEUE.remove();

        node = new ScaffoldingNode("test-node", 4);
    }

    @AfterEach
    void cleanup() {
        UpstreamQueue.UP_QUEUE.remove();
    }

    @Test
    void shouldInitializeNode() {
        assertEquals("test-node", node.name);
        assertEquals(4, node.downstreams.length);
        assertNotNull(node.parallelQueue);
        assertNotNull(node.getDrainFlag());
        assertFalse(node.terminal);
    }

    @Test
    void shouldInitializeTerminalNode() {
        ScaffoldingNode terminal = new ScaffoldingNode(
                "terminal",
                2,
                ScaffoldingNode.RoutingFunction.DEFAULT,
                true
        );

        assertTrue(terminal.terminal);
        assertNull(terminal.parallelQueue);
    }

    @Test
    void shouldSetDrainFlag() {
        node.setDrain(true);

        assertTrue(node.getDrainFlag().get());

        node.setDrain(false);

        assertFalse(node.getDrainFlag().get());
    }

    @Test
    void shouldReportDrainedWhenQueueEmpty() {
        assertTrue(node.isDrained());
    }

    @Test
    void shouldSetDownstreamMappings() {
        node.setDrain(true);

        BitSet active = new BitSet();
        active.set(0);
        active.set(2);

        ScaffoldingEdge edge1 = spy(new ScaffoldingEdge(new AtomicBoolean()));
        ScaffoldingEdge edge2 = spy(new ScaffoldingEdge(new AtomicBoolean()));

        ScaffoldingEdge[] handles = new ScaffoldingEdge[4];
        handles[0] = edge1;
        handles[2] = edge2;

        boolean result = node.setDownstreamMapping(active, handles);

        assertTrue(result);

        assertSame(edge1, node.downstreams[0]);
        assertSame(edge2, node.downstreams[2]);

        verify(edge1).setParent(node);
        verify(edge2).setParent(node);
    }

    @Test
    void shouldRejectMappingWhenNotDraining() {
        node.setDrain(false);

        BitSet active = new BitSet();

        boolean result = node.setDownstreamMapping(active, new ScaffoldingEdge[4]);

        assertFalse(result);
    }

    @Test
    void shouldCloseInactiveDownstreamsDuringRemap() {
        node.setDrain(true);

        ScaffoldingEdge existing = spy(new ScaffoldingEdge(new AtomicBoolean()));

        node.downstreams[1] = existing;

        BitSet active = new BitSet();

        node.setDownstreamMapping(active, new ScaffoldingEdge[4]);

        verify(existing).close();

        assertNull(node.downstreams[1]);
    }

    @Test
    void shouldCreateSiblingRing() {
        node.setDrain(true);

        BitSet active = new BitSet();
        active.set(0);
        active.set(1);
        active.set(2);

        ScaffoldingEdge a = new ScaffoldingEdge(new AtomicBoolean());
        ScaffoldingEdge b = new ScaffoldingEdge(new AtomicBoolean());
        ScaffoldingEdge c = new ScaffoldingEdge(new AtomicBoolean());

        ScaffoldingEdge[] handles = new ScaffoldingEdge[4];
        handles[0] = a;
        handles[1] = b;
        handles[2] = c;

        node.setDownstreamMapping(active, handles);

        assertSame(b, a.getSibling());
        assertSame(c, b.getSibling());
        assertSame(a, c.getSibling());

        assertEquals(3, a.getLayerWidth());
    }

    @Test
    void shouldRouteFramesToCorrectDownstream() {
        node.setDrain(true);

        TestTerminal first = new TestTerminal();
        TestTerminal second = new TestTerminal();

        ScaffoldingEdge edge1 = new ScaffoldingEdge(new AtomicBoolean());
        ScaffoldingEdge edge2 = new ScaffoldingEdge(new AtomicBoolean());

        edge1.addDownstream(first);
        edge2.addDownstream(second);

        BitSet active = new BitSet();
        active.set(0);
        active.set(1);

        ScaffoldingEdge[] handles = new ScaffoldingEdge[2];
        handles[0] = edge1;
        handles[1] = edge2;

        node.setDownstreamMapping(active, handles);

        TestFrame frame = spy(new TestFrame("frame"));

        doReturn(0L).when(frame).getRoutingHash();

        node.onNext(frame);

        assertEquals(1, first.received.size());
        assertEquals(0, second.received.size());
    }

    @Test
    void shouldForwardErrorsToAllDownstreams() {
        node.setDrain(true);

        ScaffoldingEdge edge1 = spy(new ScaffoldingEdge(new AtomicBoolean()));
        ScaffoldingEdge edge2 = spy(new ScaffoldingEdge(new AtomicBoolean()));

        BitSet active = new BitSet();
        active.set(0);
        active.set(1);

        ScaffoldingEdge[] handles = new ScaffoldingEdge[2];
        handles[0] = edge1;
        handles[1] = edge2;

        node.setDownstreamMapping(active, handles);

        RuntimeException error = new RuntimeException("boom");

        node.onError(error);

        verify(edge1).onError(error);
        verify(edge2).onError(error);
    }

    @Test
    void shouldCloseAllDownstreams() {
        node.setDrain(true);

        ScaffoldingEdge edge1 = spy(new ScaffoldingEdge(new AtomicBoolean()));
        ScaffoldingEdge edge2 = spy(new ScaffoldingEdge(new AtomicBoolean()));

        node.downstreams[0] = edge1;
        node.downstreams[1] = edge2;

        node.close();

        verify(edge1).close();
        verify(edge2).close();

        assertNull(node.downstreams[0]);
        assertNull(node.downstreams[1]);
    }

    @Test
    void shouldAddEdgeUpstream() {
        ScaffoldingEdge upstream = spy(new ScaffoldingEdge(new AtomicBoolean()));

        node.addUpstream(upstream);

        verify(upstream).addDownstream(node);
    }

    @Test
    void shouldAddInterceptorUpstream() {
        ScaffoldingNode.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        ScaffoldingSource source = mock(ScaffoldingSource.class);

        interceptor.addUpstream(source);

        assertSame(source, interceptor.upstream);
    }

    @Test
    void shouldIngestSource() {
        ScaffoldingSource source = mock(ScaffoldingSource.class);

        node.ingest(source);

        verify(source).addDownstream(any(ScaffoldingNode.UpstreamInterceptor.class));
    }

    @Test
    void shouldIgnoreInvalidPullArguments() {
        DrainBuffer buffer = mock(DrainBuffer.class);

        assertDoesNotThrow(() -> node.pull(null, 10));
        assertDoesNotThrow(() -> node.pull(buffer, 0));
        assertDoesNotThrow(() -> node.pull(buffer, -1));
    }

    @Test
    void shouldDelegatePullToParent() {
        ScaffoldingEdge parent = spy(new ScaffoldingEdge(new AtomicBoolean()));

        node.setParent(parent);

        DrainBuffer buffer = mock(DrainBuffer.class);

        node.pull(buffer, 10);

        verify(parent).pull(buffer, 10);
    }

    @Test
    void shouldPushUnorderedFramesIntoParallelQueue() {
        TestFrame frame = spy(new TestFrame("unordered"));

        doReturn(false).when(frame).isOrdered();

        ScaffoldingNode.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        interceptor.onNext(frame);

        assertFalse(node.parallelQueue.isEmpty());
    }

    @Test
    void shouldDirectlyRouteOrderedFrames() {
        node.setDrain(true);

        TestTerminal terminal = new TestTerminal();

        ScaffoldingEdge edge = new ScaffoldingEdge(new AtomicBoolean());

        edge.addDownstream(terminal);

        BitSet active = new BitSet();
        active.set(0);

        ScaffoldingEdge[] handles = new ScaffoldingEdge[1];
        handles[0] = edge;

        node.setDownstreamMapping(active, handles);

        TestFrame frame = spy(new TestFrame("ordered"));

        doReturn(true).when(frame).isOrdered();
        doReturn(0L).when(frame).getRoutingHash();

        ScaffoldingNode.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        interceptor.onNext(frame);

        assertEquals(1, terminal.received.size());
    }

    @Test
    void shouldRequestFromUpstream() {
        ScaffoldingNode.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        ScaffoldingSource upstream = mock(ScaffoldingSource.class);

        interceptor.upstream = upstream;

        interceptor.request(10);

        verify(upstream).request(10);
    }

    @Test
    void shouldIgnoreInvalidRequest() {
        ScaffoldingNode.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        ScaffoldingSource upstream = mock(ScaffoldingSource.class);

        interceptor.upstream = upstream;

        interceptor.request(0);

        verify(upstream, never()).request(anyLong());
    }

    @Test
    void shouldCancelUpstream() {
        ScaffoldingNode.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        ScaffoldingSource upstream = mock(ScaffoldingSource.class);

        interceptor.upstream = upstream;

        interceptor.complete();

        verify(upstream).complete();

        assertTrue(interceptor.complete.get());
    }

    @Test
    void shouldMarkCompleteOnCompletion() {
        ScaffoldingNode.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        interceptor.onComplete();

        assertTrue(interceptor.complete.get());
    }

    @Test
    void shouldMarkCompleteOnError() {
        ScaffoldingNode.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        interceptor.onError(new RuntimeException("boom"));

        assertTrue(interceptor.complete.get());
    }

    @Test
    void shouldReportInterceptorCompletionState() {
        ScaffoldingNode.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        assertFalse(interceptor.isComplete());

        interceptor.onComplete();

        assertTrue(interceptor.isComplete());
    }
}