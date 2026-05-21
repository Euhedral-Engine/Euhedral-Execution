package euhedral.io.flow_control;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.IngestSink;
import euhedral.io.generics.ScaffoldingSource;
import euhedral.io.generics.ScaffoldingTerminal;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

@SuppressWarnings("unused")
public class ArrayIngestSink implements IngestSink {

    private final Delegate delegate;

    public ArrayIngestSink(AbstractFrame[] frames) {
        this.delegate = new Delegate(frames);
    }

    public ScaffoldingSource getDelegate() {
        return this.delegate;
    }

    public void reset() {
        this.delegate.reset();
    }

    @Override
    public void close() {
        delegate.close();
    }

    protected static final class ArrayWrapper {

        int start;
        int end;
        AbstractFrame[] array;
    }

    private static class Delegate implements IngestSink.Delegate {

        private static final VarHandle TERMINAL;

        static {
            try {
                TERMINAL = MethodHandles.lookup()
                        .findVarHandle(Delegate.class, "terminal", ScaffoldingTerminal.class);
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        private static long accumulate(long curr, long next) {
            if (curr + next < 0) {
                return Long.MAX_VALUE;
            }
            return curr + next;
        }

        private final PaddedAtomicLong demand = new PaddedAtomicLong(0);
        private final AbstractFrame[] array;
        int start;
        private ScaffoldingTerminal terminal;

        public Delegate(AbstractFrame[] array) {
            this.array = array;
        }

        @Override
        public void request(long demand) {
            var terminal = (ScaffoldingTerminal) TERMINAL.getOpaque(this);
            if (terminal == null || demand <= 0 || start >= array.length) {
                return;
            }
            demand = this.demand.accumulateAndGet(demand, Delegate::accumulate);
            int batch = (int) Math.min(demand, Integer.MAX_VALUE);

            while (start < batch && start < array.length) {
                terminal.onNext(array[start++]);
            }
            VarHandle.releaseFence();
            if (start >= array.length) {
                close();
            }
        }

        @Override
        public void cancel() {
            close();
        }

        @Override
        public void addDownstream(ScaffoldingTerminal terminal) {
            if (!TERMINAL.compareAndSet(this, null, terminal)) {
                terminal.onError(new IllegalStateException("Already Subscribed"));
            }
            terminal.addUpstream(this);
        }

        @Override
        public void close() {
            var t = (ScaffoldingTerminal) TERMINAL.getAndSet(this, null);
            this.demand.lazySet(0);
            if (t != null) {
                t.onComplete();
            }
        }

        public void reset() {
            TERMINAL.setRelease(this, null);
            this.demand.setPlain(0);
            this.start = 0;
            VarHandle.releaseFence();
        }
    }
}
