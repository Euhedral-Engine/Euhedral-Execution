package euhedral.io.generics;

import euhedral.atomics.PaddedAtomicLong;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public abstract class IngestSink {

    /// Used by the [ControlPlane][euhedral.io.control_plane.ControlPlane] to connect to this sink.
    public abstract ScaffoldingSource getDelegate();

    /// Marks the sink as complete and disconnects it from the
    /// [ControlPlane][euhedral.io.control_plane.ControlPlane].
    public abstract void complete();

    protected static abstract class Delegate implements ScaffoldingSource {

        protected static final VarHandle TERMINAL;

        static {
            try {
                TERMINAL = MethodHandles.lookup()
                        .findVarHandle(Delegate.class, "terminal", ScaffoldingTerminal.class);
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        protected static long accumulate(long curr, long next) {
            if (curr + next < 0) {
                return Long.MAX_VALUE;
            }
            return curr + next;
        }

        protected final PaddedAtomicLong demand = new PaddedAtomicLong(0);
        protected ScaffoldingTerminal terminal;

        @Override
        public void addDownstream(ScaffoldingTerminal terminal) {
            if (!TERMINAL.compareAndSet(this, null, terminal)) {
                terminal.onError(new IllegalStateException("Already Subscribed"));
            }
            terminal.addUpstream(this);
        }

        protected ScaffoldingTerminal getTerminal() {
            return (ScaffoldingTerminal) TERMINAL.getOpaque(this);
        }

        protected long addAndGetDemand(long demand) {
            return this.demand.accumulateAndGet(demand, Delegate::accumulate);
        }

        protected void reset() {
            TERMINAL.setRelease(this, null);
            this.demand.setPlain(0);
        }

        @Override
        public final void request(long demand) {
            var terminal = getTerminal();
            if (terminal == null || demand <= 0) {
                return;
            }
            hookOnRequest(terminal, addAndGetDemand(demand));
        }

        public abstract void hookOnRequest(ScaffoldingTerminal terminal, long demand);

        @Override
        public void complete() {
            var t = (ScaffoldingTerminal) TERMINAL.getAndSet(this, null);
            this.demand.lazySet(0);
            if (t != null) {
                t.onComplete();
            }
        }
    }
}
