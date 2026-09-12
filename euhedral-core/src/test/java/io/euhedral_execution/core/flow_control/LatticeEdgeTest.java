package io.euhedral_execution.core.flow_control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.euhedral_execution.core.flow_control.UpstreamQueue.UpstreamHandle;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import test_utils.TestFrame;
import test_utils.TestReceiver;

@Isolated
class LatticeEdgeTest {

    private AtomicBoolean drain;
    private LatticeEdge edge;
    private MockedStatic<SystemInfo> mockSysInfo;
    private MockedStatic<ThreadTools> mockThreadTools;
    private int threadCountBefore;
    private long upstreamCountBefore;

    @BeforeAll
    static void initializeSharedRoutingStateFromTheRealTopology() {
        new LatticeEdge(new AtomicBoolean());
    }

    @BeforeEach
    void setup() {
        UpstreamQueue.UP_QUEUE.remove();
        ThreadTools.getCpu();

        mockSysInfo = Mockito.mockStatic(SystemInfo.class);
        mockSysInfo.when(SystemInfo::getMaxCoreId).thenReturn(64);
        mockSysInfo.when(SystemInfo::getCoreCount).thenReturn(64);

        CoreInfo core = new CoreInfo("", true, 0, 0);
        mockSysInfo.when(() -> SystemInfo.getCoreInfo(anyInt())).thenReturn(core);

        CpuInfo cpu = new CpuInfo(0, 0, 0);
        mockSysInfo.when(() -> SystemInfo.getCpuInfo(anyInt())).thenReturn(cpu);

        BitSet pCores = new BitSet();
        pCores.set(0, 64);
        mockSysInfo.when(SystemInfo::getPCoreSet).thenReturn(UnmodifiableBitSet.wrap(pCores));

        mockThreadTools = Mockito.mockStatic(ThreadTools.class);
        mockThreadTools.when(ThreadTools::getCpu).thenReturn(0);

        drain = new AtomicBoolean(false);
        edge = new LatticeEdge(drain);
        threadCountBefore = edge.getThreadCount();
        upstreamCountBefore = edge.getUpstreamHandleCount();
    }

    @AfterEach
    void cleanup() {
        try {
            if (edge != null) {
                edge.removeThread();
            }
        } finally {
            UpstreamQueue.UP_QUEUE.remove();
            if (mockThreadTools != null) {
                mockThreadTools.close();
            }
            if (mockSysInfo != null) {
                mockSysInfo.close();
            }
        }
    }

    @Test
    void shouldCreateThreadQueueOnRegister() {
        edge.register();

        assertEquals(threadCountBefore + 1, edge.getThreadCount());
    }

    @Test
    void shouldReuseThreadQueue() {
        UpstreamQueue first = edge.getThreadUpstreamQueue();
        UpstreamQueue second = edge.getThreadUpstreamQueue();

        assertSame(first, second);
        assertEquals(threadCountBefore, edge.getThreadCount());
    }

    /// Verifies repeated active-partition registration is idempotent.
    @Test
    void shouldCountOneRegistrationPerActivePartition() {
        edge.register();
        edge.register();

        assertEquals(threadCountBefore + 1, edge.getThreadCount());
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
    void shouldIgnoreNonPositiveRequest() {
        edge.request(-1);
        edge.request(0);

        assertEquals(threadCountBefore, edge.getThreadCount());
    }

    @Test
    void shouldIgnoreNonPositivePull() {
        Consumer<AbstractFrame> consumer = frame -> {};

        assertEquals(0, edge.pull(consumer, frame -> false, -1));
        assertEquals(0, edge.pull(consumer, frame -> false, 0));
        assertEquals(threadCountBefore, edge.getThreadCount());
    }

    @Test
    void shouldIgnoreRequestWhenDrainActive() {
        drain.set(true);

        edge.request(10);

        assertEquals(threadCountBefore, edge.getThreadCount());
    }

    @Test
    void shouldIgnorePullWhenDrainActive() {
        drain.set(true);

        assertDoesNotThrow(() -> edge.pull(frame -> {}, frame -> false, 10));
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

        Consumer<AbstractFrame> consumer = frame -> {};

        Function<AbstractFrame, Boolean> stopCondition = frame -> false;

        edge.pull(consumer, stopCondition, 123);

        verify(parent).pull(consumer, stopCondition, 123);
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
        assertEquals("Already added as an upstream by a terminal downstream", second.error.getMessage());
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

        assertEquals(upstreamCountBefore + 1, edge.getUpstreamHandleCount());
        edge.removeUpstream();
        assertEquals(upstreamCountBefore, edge.getUpstreamHandleCount());
    }

    @Test
    void shouldIgnoreCompleteUpstreamsDuringRegistration() {
        UpstreamHandle upstream = mock(UpstreamHandle.class);

        when(upstream.isComplete()).thenReturn(true);

        edge.addUpstream(upstream);

        edge.register();

        assertEquals(threadCountBefore + 1, edge.getThreadCount());
        edge.removeUpstream();
    }

    @Test
    void shouldTransferToParent() {
        UpstreamHandle upstream = mock(UpstreamHandle.class);

        when(upstream.isComplete()).thenReturn(false);

        edge.addUpstream(upstream);

        edge.register();

        LatticeEdge parent = new LatticeEdge(new AtomicBoolean());

        edge.setParent(parent);

        assertEquals(upstreamCountBefore + 1, parent.getUpstreamHandleCount());
        assertEquals(threadCountBefore + 1, parent.getThreadCount());
        edge.removeUpstream();
    }

    @Test
    void shouldRemoveThread() {
        edge.register();

        assertEquals(threadCountBefore + 1, edge.getThreadCount());

        edge.removeThread();

        assertEquals(threadCountBefore, edge.getThreadCount());

        edge.removeThread();

        assertEquals(threadCountBefore, edge.getThreadCount());
    }

    @Test
    void shouldIgnoreNullThreadRemoval() {
        assertDoesNotThrow(() -> edge.removeThread());
    }

    @Test
    void shouldAlwaysReportIncomplete() {
        assertFalse(edge.isComplete());
    }

    @Test
    void shouldReturnNegativeRankForUnregisteredCore() {
        assertEquals(-1, edge.getThreadRank(99));
    }

    @Test
    void shouldRankRegisteredCore() {
        edge.register();

        assertEquals(1, edge.getThreadRank(0));
        assertEquals(-1, edge.getThreadRank(1));
    }

    @Test
    void shouldDelegateThreadRankToParent() {
        edge.register();

        LatticeEdge child = new LatticeEdge(new AtomicBoolean());
        child.setParent(edge);

        assertEquals(1, child.getThreadRank(0));
    }
}
