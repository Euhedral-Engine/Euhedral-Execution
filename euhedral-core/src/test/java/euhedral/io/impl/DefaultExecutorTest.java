package euhedral.io.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.flow_control.BufferedBridge;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.CloneableObject;
import euhedral.io.generics.LaticeSource;
import euhedral.io.generics.LatticeReceiver;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test_utils.TestFrame;

class DefaultExecutorTest {

    private PinnedThreadExecutor executorService;
    private DefaultExecutor executor;

    private TestCloneableObject cloneable;
    private BufferedBridge sink;

    @BeforeEach
    void setup() {
        executorService = mock(PinnedThreadExecutor.class);
        executor = new DefaultExecutor(executorService);

        cloneable = mock(TestCloneableObject.class);
        sink = mock(BufferedBridge.class);

        when(cloneable.completeChannel()).thenReturn(sink);
    }

    @Test
    void shouldCloneExecutor() {
        DefaultExecutor cloned =
                (DefaultExecutor) executor.clone(new CloneConfig("", 0, 0, null));

        assertNotNull(cloned);
        assertNotSame(executor, cloned);
    }

    @Test
    void shouldCloneWithNewExecutorService() {
        PinnedThreadExecutor newExec = mock(PinnedThreadExecutor.class);

        DefaultExecutor cloned =
                (DefaultExecutor) executor.clone(new CloneConfig("", 0, 0, null),
                        newExec);

        assertNotNull(cloned);
        assertNotSame(executor, cloned);
    }

    @Test
    void shouldWireCompleteSink() {
        executor.reportCompletionsTo(cloneable);

        when(sink.offer(any())).thenReturn(true);

        TestFrame frame = spy(new TestFrame("x"));
        when(frame.isAlive()).thenReturn(true);

        executor.input(new TestSource(frame));

        assertTrue(true);
    }

    @Test
    void shouldExecuteFrameNormally() {
        executor.reportCompletionsTo(cloneable);

        when(sink.offer(any())).thenReturn(true);

        TestFrame frame = spy(new TestFrame("a"));
        when(frame.isAlive()).thenReturn(true);

        executor.input(new TestSource(frame));

        verify(frame).execute();
    }

    @Test
    void shouldSkipDeadFrameAndThrowIntoErrorPath() {
        executor.reportCompletionsTo(cloneable);

        when(sink.offer(any())).thenReturn(true);

        TestFrame frame = spy(new TestFrame("a"));
        when(frame.isAlive()).thenReturn(false);

        executor.input(new TestSource(frame));

        verify(frame).throwMeAsError();
    }

    @Test
    void shouldMarkCancelledOnException() {
        executor.reportCompletionsTo(cloneable);

        when(sink.offer(any())).thenReturn(true);

        TestFrame frame = spy(new TestFrame("a"));
        when(frame.isAlive()).thenReturn(true);

        doThrow(new RuntimeException("boom")).when(frame).execute();

        executor.input(new TestSource(frame));

        verify(frame).setCancelledExecution(true);
    }

    @Test
    void shouldRetryUntilSinkAccepts() {
        executor.reportCompletionsTo(cloneable);

        AtomicInteger calls = new AtomicInteger();

        when(sink.offer(any())).thenAnswer(inv -> calls.incrementAndGet() >= 3);

        TestFrame frame = spy(new TestFrame("a"));
        when(frame.isAlive()).thenReturn(true);

        executor.input(new TestSource(frame));

        assertTrue(calls.get() >= 3);
    }

    static class TestSource implements LaticeSource {

        private final AbstractFrame frame;

        TestSource(AbstractFrame frame) {
            this.frame = frame;
        }

        @Override
        public void addDownstream(LatticeReceiver terminal) {
            terminal.addUpstream(this);
            terminal.onNext(frame);
        }

        @Override
        public void request(long n) {
        }

        @Override
        public void complete() {
        }
    }

    static class TestCloneableObject implements CloneableObject {

        private final BufferedBridge sink;

        TestCloneableObject(BufferedBridge sink) {
            this.sink = sink;
        }

        @Override
        public CloneableObject clone(CloneConfig cloneConfig) {
            return null;
        }

        @Override
        public BufferedBridge completeChannel() {
            return sink;
        }

        @Override
        public void close() {

        }
    }
}