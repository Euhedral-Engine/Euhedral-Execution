package euhedral.io.ingest;

import euhedral.io.control_plane.ControlPlaneLattice;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeReceiver;
import euhedral.io.generics.LatticeSource;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;

public abstract class AbstractIngestSink {

    /// Used by the [ControlPlaneLattice][ControlPlaneLattice] to connect to this sink.
    public abstract LatticeSource getDelegate();

    /// Marks the sink as complete and disconnects it from the
    /// [ControlPlaneLattice][ControlPlaneLattice].
    public abstract void complete();

    protected static abstract class Delegate implements LatticeSource {

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
            long sum = curr + next;
            return sum < 0 ? Long.MAX_VALUE : sum;
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

        @Override
        public final long pull(Consumer<AbstractFrame> consumer, long demand) {
            var terminal = getTerminal();
            if (terminal == null || demand <= 0) {
                return 0;
            }
            return hookOnPull(consumer, demand);
        }

        public abstract long hookOnPull(Consumer<AbstractFrame> consumer, long demand);

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
