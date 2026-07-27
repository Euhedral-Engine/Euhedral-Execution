package io.euhedral_execution.core.ingest;

import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;

/// Wraps an array to allow it to be ingested by the [ControlPlaneLattice][ControlPlaneLattice]
@SuppressWarnings("unused")
public final class ArrayIngestSink extends AbstractIngestSink {

    private final Delegate delegate;

    public ArrayIngestSink(@NonNull AbstractFrame[] frames) {
        Objects.requireNonNull(frames);
        this.delegate = new Delegate(frames);
        VarHandle.fullFence();
    }

    /// Returns the delegate the ControlPlaneLattice will use to ingest the array.
    public LatticeSource getDelegate() {
        return this.delegate;
    }

    public @NonNull AbstractFrame[] getFrameArray() {
        return this.delegate.array;
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

    protected static final class Delegate extends AbstractIngestSink.Delegate {

        private final AbstractFrame[] array;
        int start;

        public Delegate(AbstractFrame[] array) {
            this.array = array;
        }

        @Override
        public long hookOnPull(Consumer<AbstractFrame> consumer,
                Function<AbstractFrame, Boolean> stopCondition, long demand) {
            if (this.start >= this.array.length) {
                super.complete();
                return 0;
            }

            long end = this.start + demand;

            long total = 0;
            while (this.start < end && this.start < this.array.length) {
                AbstractFrame frame = this.array[this.start];
                if(stopCondition.apply(frame)) {
                    break;
                }
                this.start++;

                Objects.requireNonNull(frame);
                consumer.accept(frame);
                total++;
            }
            VarHandle.releaseFence();
            if (start >= array.length) {
                super.complete();
            }
            return total;
        }

        @Override
        public void hookOnRequest(LatticeReceiver terminal, long demand) {
            if (start >= array.length) {
                super.complete();
                return;
            }

            long end = start + demand;

            int count = 0;
            while (start < end && start < array.length) {
                AbstractFrame frame = array[start++];
                Objects.requireNonNull(frame);
                terminal.push(frame);
                count++;
            }
            addAndGetDemand(-count);
            VarHandle.releaseFence();
            if (start >= array.length) {
                super.complete();
            }
        }
    }
}
