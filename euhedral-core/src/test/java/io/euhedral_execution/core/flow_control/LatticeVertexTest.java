package io.euhedral_execution.core.flow_control;

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

import io.euhedral_execution.core.flow_control.LatticeVertex.RoutingFunction;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeSource;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import test_utils.TestFrame;
import test_utils.TestReceiver;

class LatticeVertexTest {

    private LatticeVertex node;

    @BeforeEach
    void setup() {
        UpstreamQueue.UP_QUEUE.remove();

        node = new LatticeVertex("test-node", 4, RoutingFunction.DEFAULT,
                32, RoutingPolicy.ANYWHERE);
        BitSet active = new BitSet(4);
        active.set(0, 4);

        LatticeEdge mockEdge = Mockito.mock(LatticeEdge.class);
        LatticeEdge[] handles = new LatticeEdge[4];
        Arrays.fill(handles, mockEdge);

        node.setDrain(true);
        node.setDownstreamMapping(active, handles);
        node.setDrain(false);
    }

    @AfterEach
    void cleanup() {
        UpstreamQueue.UP_QUEUE.remove();
    }

    @Test
    void shouldInitializeNode() {
        assertEquals(4, node.downstreams.length);
        assertNotNull(node.remoteCache);
        assertNotNull(node.getDrainFlag());
        assertTrue(node.hasCache);
    }

    @Test
    void shouldInitializeTerminalNode() {
        LatticeVertex terminal = new LatticeVertex(
                "terminal",
                2,
                LatticeVertex.RoutingFunction.DEFAULT,
                0, RoutingPolicy.ANYWHERE
        );

        assertFalse(terminal.hasCache);
        assertNull(terminal.remoteCache);
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

        LatticeEdge edge1 = spy(new LatticeEdge(new AtomicBoolean()));
        LatticeEdge edge2 = spy(new LatticeEdge(new AtomicBoolean()));

        LatticeEdge[] handles = new LatticeEdge[4];
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

        boolean result = node.setDownstreamMapping(active, new LatticeEdge[4]);

        assertFalse(result);
    }

    @Test
    void shouldCloseInactiveDownstreamsDuringRemap() {
        node.setDrain(true);

        LatticeEdge existing = spy(new LatticeEdge(new AtomicBoolean()));

        node.downstreams[1] = existing;

        BitSet active = new BitSet();

        node.setDownstreamMapping(active, new LatticeEdge[4]);

        verify(existing).close();

        assertNull(node.downstreams[1]);
    }

    @Test
    void shouldRouteFramesToCorrectDownstream() {
        node = new LatticeVertex("test-node", 4, RoutingFunction.DEFAULT, 0,
                RoutingPolicy.ANYWHERE);
        node.setDrain(true);

        TestReceiver first = new TestReceiver();
        TestReceiver second = new TestReceiver();

        LatticeEdge edge1 = new LatticeEdge(new AtomicBoolean());
        LatticeEdge edge2 = new LatticeEdge(new AtomicBoolean());

        edge1.addDownstream(first);
        edge2.addDownstream(second);

        BitSet active = new BitSet();
        active.set(0);
        active.set(1);

        LatticeEdge[] handles = new LatticeEdge[2];
        handles[0] = edge1;
        handles[1] = edge2;

        node.setDownstreamMapping(active, handles);

        TestFrame frame = spy(new TestFrame("frame"));

        doReturn(0L).when(frame).getRoutingHash();

        node.push(frame);

        assertEquals(1, first.received.size());
        assertEquals(0, second.received.size());
    }

    @Test
    void shouldForwardErrorsToAllDownstreams() {
        node.setDrain(true);

        LatticeEdge edge1 = spy(new LatticeEdge(new AtomicBoolean()));
        LatticeEdge edge2 = spy(new LatticeEdge(new AtomicBoolean()));

        BitSet active = new BitSet();
        active.set(0);
        active.set(1);

        LatticeEdge[] handles = new LatticeEdge[2];
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

        LatticeEdge edge1 = spy(new LatticeEdge(new AtomicBoolean()));
        LatticeEdge edge2 = spy(new LatticeEdge(new AtomicBoolean()));

        node.downstreams[0] = edge1;
        node.downstreams[1] = edge2;

        node.close();

        verify(edge1).close();
        verify(edge2).close();
    }

    @Test
    void shouldAddEdgeUpstream() {
        LatticeEdge upstream = spy(new LatticeEdge(new AtomicBoolean()));

        node.addUpstream(upstream);

        verify(upstream).addDownstream(node);
    }

    @Test
    void shouldAddInterceptorUpstream() {
        LatticeVertex.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        LatticeSource source = mock(LatticeSource.class);

        interceptor.addUpstream(source);

        assertSame(source, interceptor.upstream);
    }

    @Test
    void shouldIngestSource() {
        LatticeSource source = mock(LatticeSource.class);

        node.ingest(source);

        verify(source).addDownstream(any(LatticeVertex.UpstreamInterceptor.class));
    }

    @Test
    void shouldIgnoreInvalidPullArguments() {

        assertDoesNotThrow(() -> node.pull(null, 10));
        assertDoesNotThrow(() -> node.pull(frame -> {}, 0));
        assertDoesNotThrow(() -> node.pull(frame -> {}, -1));
    }

    @Test
    void shouldDelegatePullToParent() {
        LatticeEdge parent = spy(new LatticeEdge(new AtomicBoolean()));

        node.setParent(parent);

        Consumer<AbstractFrame> consumer = frame -> {};

        node.pull(consumer, 10);

        verify(parent).pull(consumer, 10);
    }

    @Test
    void shouldPushUnorderedFramesIntoParallelQueue() {
        TestFrame frame = spy(new TestFrame("unordered"));

        doReturn(false).when(frame).isOrdered();

        LatticeVertex.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        interceptor.push(frame);

        boolean hasItem = false;
        for(var queue : node.remoteCache) {
            if(queue != null) {
                hasItem |= !queue.isEmpty();
            }
        }
        assertTrue(hasItem);
    }

    @Test
    void shouldDirectlyRouteOrderedFrames() {
        node = new LatticeVertex("test-node", 4, RoutingFunction.DEFAULT, 0,
                RoutingPolicy.ANYWHERE);
        node.setDrain(true);

        TestReceiver terminal = new TestReceiver();

        LatticeEdge edge = new LatticeEdge(new AtomicBoolean());

        edge.addDownstream(terminal);

        BitSet active = new BitSet();
        active.set(0);

        LatticeEdge[] handles = new LatticeEdge[1];
        handles[0] = edge;

        node.setDownstreamMapping(active, handles);

        TestFrame frame = spy(new TestFrame("ordered"));

        doReturn(true).when(frame).isOrdered();
        doReturn(0L).when(frame).getRoutingHash();

        LatticeVertex.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        interceptor.push(frame);

        assertEquals(1, terminal.received.size());
    }

    @Test
    void shouldRequestFromUpstream() {
        LatticeVertex.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        LatticeSource upstream = mock(LatticeSource.class);

        interceptor.upstream = upstream;

        interceptor.request(10);

        verify(upstream).request(10);
    }

    @Test
    void shouldIgnoreInvalidRequest() {
        LatticeVertex.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        LatticeSource upstream = mock(LatticeSource.class);

        interceptor.upstream = upstream;

        interceptor.request(0);

        verify(upstream, never()).request(anyLong());
    }

    @Test
    void shouldCancelUpstream() {
        LatticeVertex.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        LatticeSource upstream = mock(LatticeSource.class);

        interceptor.upstream = upstream;

        interceptor.complete();

        verify(upstream).complete();

        assertTrue(interceptor.isComplete.get());
    }

    @Test
    void shouldMarkCompleteOnCompletion() {
        LatticeVertex.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        interceptor.onComplete();

        assertTrue(interceptor.isComplete.get());
    }

    @Test
    void shouldMarkCompleteOnError() {
        LatticeVertex.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        interceptor.onError(new RuntimeException("boom"));

        assertTrue(interceptor.isComplete.get());
    }

    @Test
    void shouldReportInterceptorCompletionState() {
        LatticeVertex.UpstreamInterceptor interceptor =
                node.new UpstreamInterceptor();

        assertFalse(interceptor.isComplete());

        interceptor.onComplete();

        assertTrue(interceptor.isComplete());
    }
}