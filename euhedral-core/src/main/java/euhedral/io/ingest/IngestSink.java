package euhedral.io.ingest;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.io.control_plane.ControlPlaneLattice;
import euhedral.io.generics.LaticeSource;
import euhedral.io.generics.LatticeReceiver;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public abstract class IngestSink {

    /// Used by the [ControlPlaneLattice][ControlPlaneLattice] to connect to this sink.
    public abstract LaticeSource getDelegate();

    /// Marks the sink as complete and disconnects it from the
    /// [ControlPlaneLattice][ControlPlaneLattice].
    public abstract void complete();

    protected static abstract class Delegate implements LaticeSource {

        protected static final VarHandle TERMINAL;

        static {
            try {
                TERMINAL = MethodHandles.lookup()
                        .findVarHandle(Delegate.class, "terminal", LatticeReceiver.class);
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
        protected LatticeReceiver terminal;

        @Override
        public void addDownstream(LatticeReceiver terminal) {
            if (!TERMINAL.compareAndSet(this, null, terminal)) {
                terminal.onError(new IllegalStateException("Already Subscribed"));
            }
            terminal.addUpstream(this);
        }

        protected LatticeReceiver getTerminal() {
            return (LatticeReceiver) TERMINAL.getOpaque(this);
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

        public abstract void hookOnRequest(LatticeReceiver terminal, long demand);

        @Override
        public void complete() {
            var t = (LatticeReceiver) TERMINAL.getAndSet(this, null);
            this.demand.lazySet(0);
            if (t != null) {
                t.onComplete();
            }
        }
    }
}
