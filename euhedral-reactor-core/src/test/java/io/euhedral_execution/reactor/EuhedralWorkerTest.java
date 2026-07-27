package io.euhedral_execution.reactor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.reactor.common.TaskFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

class EuhedralWorkerTest {

    @Test
    void schedulesImmediateTasksThroughItsPullSource() {
        EuhedralWorker worker = new EuhedralWorker(4, 1);
        RecordingReceiver receiver = new RecordingReceiver();
        AtomicInteger executions = new AtomicInteger();
        worker.getDelegate().addDownstream(receiver);

        Disposable disposable = worker.schedule(executions::incrementAndGet);
        worker.getDelegate().request(0);
        assertTrue(receiver.frames.isEmpty());

        worker.getDelegate().request(1);
        assertEquals(1, receiver.frames.size());
        TaskFrame frame = (TaskFrame) receiver.frames.get(0);
        assertSame(disposable, frame);
        assertFalse(frame.isDisposed());

        frame.execute();
        frame.doFinally();
        assertEquals(1, executions.get());

        disposable.dispose();
        assertTrue(disposable.isDisposed());
        worker.dispose();
        assertTrue(worker.isDisposed());
        assertEquals(1, receiver.completions);
    }

    @Test
    void rejectsNullTasksAndUnits() {
        EuhedralWorker worker = new EuhedralWorker(4, 1);
        try {
            assertThrows(NullPointerException.class, () -> worker.schedule(null));
            assertThrows(NullPointerException.class,
                    () -> worker.schedule(() -> {
                    }, 1, null));
        } finally {
            worker.dispose();
        }
    }

    private static final class RecordingReceiver implements LatticeReceiver {

        private final List<AbstractFrame> frames = new ArrayList<>();
        private int completions;

        @Override
        public void push(AbstractFrame frame) {
            this.frames.add(frame);
        }

        @Override
        public void onComplete() {
            this.completions++;
        }

        @Override
        public void onError(Throwable error) {
            throw new AssertionError(error);
        }

        @Override
        public void addUpstream(LatticeSource upstream) {
        }
    }
}
