package io.euhedral_execution.benchmarks.utils;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.ingest.AbstractIngestSink;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

public class RepeatingSink extends AbstractIngestSink {

    private final Delegate delegate;

    public RepeatingSink(AbstractFrame[] frames) {
        Objects.requireNonNull(frames);
        this.delegate = new Delegate(frames);
        VarHandle.fullFence();
    }

    /// Returns the delegate the ControlPlaneLattice will use to ingest the array.
    public LatticeSource getDelegate() {
        return this.delegate;
    }

    /// Disconnects from the [ControlPlaneLattice] immediately.
    @Override
    public void complete() {
        delegate.complete();
    }

    @Override
    public boolean isComplete() {
        return this.delegate.isComplete();
    }

    /// Enables or pauses this already-connected source without rebuilding scheduler topology.
    public void setEnabled(boolean enabled) {
        this.delegate.enabled.setRelease(enabled);
    }

    public boolean isEnabled() {
        return this.delegate.enabled.getAcquire();
    }

    /// Restores deterministic source position while the owning lattice has ingest frozen.
    public void resetForNextIteration() {
        this.delegate.resetForNextIteration();
    }

    protected static final class Delegate extends AbstractIngestSink.Delegate {

        final AtomicBoolean complete = new AtomicBoolean();
        final AtomicBoolean enabled = new AtomicBoolean(true);
        private final AbstractFrame[] array;
        int start;

        public Delegate(AbstractFrame[] array) {
            this.array = array;
        }

        void resetForNextIteration() {
            this.start = 0;
            this.demand.setRelease(0L);
        }

        @Override
        public long hookOnPull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            if (!this.enabled.getOpaque()) {
                return 0L;
            }
            long total = 0;
            while (total < demand && !this.complete.getOpaque()) {
                AbstractFrame frame = this.array[this.start];
                if (stopCondition.apply(frame)) {
                    break;
                }
                this.start++;

                Objects.requireNonNull(frame);
                consumer.accept(frame);
                total++;
                this.start %= this.array.length;
            }
            VarHandle.releaseFence();
            return total;
        }

        @Override
        public void hookOnRequest(LatticeReceiver terminal, long demand) {
            if (!this.enabled.getOpaque()) {
                addAndGetDemand(-demand);
                return;
            }
            int total = 0;
            while (total < demand && !this.complete.getOpaque()) {
                AbstractFrame frame = this.array[this.start++];
                Objects.requireNonNull(frame);
                terminal.push(frame);
                total++;
                this.start %= this.array.length;
            }
            addAndGetDemand(-total);
            VarHandle.releaseFence();
        }

        @Override
        public void complete() {
            this.complete.setRelease(true);
            super.complete();
        }
    }
}
