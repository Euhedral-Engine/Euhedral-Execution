package euhedral.io.impl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.CloneableObject;
import euhedral.io.generics.LatticeReceiver;
import euhedral.io.generics.LatticeSource;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import test_utils.TestFrame;

class DefaultExecutorTest {

    private DefaultExecutor executor;

    @BeforeEach
    void setup() {
        executor = new DefaultExecutor(0);
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
    void shouldExecuteFrameNormally() {
        TestFrame frame = spy(new TestFrame("a"));
        when(frame.isAlive()).thenReturn(true);

        executor.input(new TestSource(frame));

        verify(frame).execute();
        verify(frame).execute();
    }

    @Test
    void shouldSkipDeadFrameAndThrowIntoErrorPath() {
        TestFrame frame = spy(new TestFrame("a"));
        when(frame.isAlive()).thenReturn(false);

        executor.input(new TestSource(frame));

        verify(frame).throwCancelSignal();
        verify(frame).doFinally();
    }

    static class TestSource implements LatticeSource {

        private final AbstractFrame frame;

        TestSource(AbstractFrame frame) {
            this.frame = frame;
        }

        @Override
        public void addDownstream(LatticeReceiver terminal) {
            terminal.addUpstream(this);
            terminal.push(frame);
        }

        @Override
        public long pull(Consumer<AbstractFrame> consumer, long demand) {
            return demand;
        }

        @Override
        public void request(long n) {
        }

        @Override
        public void complete() {
        }
    }

    static class TestCloneableObject implements CloneableObject {

        @Override
        public CloneableObject clone(CloneConfig cloneConfig) {
            return null;
        }

        @Override
        public void close() {

        }
    }
}