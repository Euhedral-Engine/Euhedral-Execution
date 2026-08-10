package io.euhedral_execution.core.ingest;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.utils.CommonVarHandles;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;
import java.util.function.Function;

public abstract class AbstractIngestSink {

    /// Used by the
    /// [ControlPlaneLattice][io.euhedral_execution.core.control_plane.ControlPlaneLattice] to
    /// connect to this sink.
    public abstract LatticeSource getDelegate();

    /// Marks the sink as complete and disconnects it from the
    /// [ControlPlaneLattice][io.euhedral_execution.core.control_plane.ControlPlaneLattice].
    public abstract void complete();

    public abstract boolean isComplete();

    protected abstract static class Delegate implements LatticeSource {

        protected static final VarHandle COMPLETE = CommonVarHandles.complete(Delegate.class);
        protected static final VarHandle DOWNSTREAM = CommonVarHandles.downstream(Delegate.class);
        protected final PaddedAtomicLong demand = new PaddedAtomicLong(0);
        protected boolean complete;
        protected LatticeReceiver downstream;

        protected static long accumulate(long curr, long next) {
            long sum = curr + next;
            return sum < 0 ? Long.MAX_VALUE : sum;
        }

        @Override
        public void addDownstream(LatticeReceiver terminal) {
            if (!DOWNSTREAM.compareAndSet(this, null, terminal)) {
                terminal.onError(new IllegalStateException("Already has a downstream"));
            }
        }

        protected LatticeReceiver getDownstream() {
            return (LatticeReceiver) DOWNSTREAM.getOpaque(this);
        }

        protected long addAndGetDemand(long demand) {
            return this.demand.accumulateAndGet(demand, Delegate::accumulate);
        }

        @Override
        public final long pull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            var receiver = getDownstream();
            if (isComplete() || receiver == null || demand <= 0) {
                return 0;
            }
            return hookOnPull(consumer, stopCondition, demand);
        }

        public abstract long hookOnPull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand);

        @Override
        public final void request(long demand) {
            var receiver = getDownstream();
            if (isComplete() || receiver == null || demand <= 0) {
                return;
            }
            hookOnRequest(receiver, addAndGetDemand(demand));
        }

        public abstract void hookOnRequest(LatticeReceiver terminal, long demand);

        @Override
        public void complete() {
            if (COMPLETE.compareAndSet(this, false, true)) {
                var t = (LatticeReceiver) DOWNSTREAM.getAndSet(this, null);
                this.demand.lazySet(0);
                if (t != null) {
                    t.onComplete();
                }
            }
        }

        @Override
        public boolean isComplete() {
            return (boolean) COMPLETE.getOpaque(this);
        }
    }
}
