package euhedral.io.ingest;

import euhedral.io.control_plane.ControlPlaneLattice;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeReceiver;
import euhedral.io.generics.LatticeSource;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/// Wraps an array to allow it to be ingested by the
/// [ControlPlaneLattice][ControlPlaneLattice]
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

    protected static final class Delegate extends AbstractIngestSink.Delegate {

        private final AbstractFrame[] array;
        int start;

        public Delegate(AbstractFrame[] array) {
            this.array = array;
        }

        @Override
        public long hookOnPull(Consumer<AbstractFrame> consumer, long demand) {
            if(start >= array.length) {
                complete();
                return 0;
            }

            long end = start + demand;

            long total = 0;
            while (start < end && start < array.length) {
                consumer.accept(array[start++]);
                total++;
            }
            VarHandle.releaseFence();
            if (start >= array.length) {
                complete();
            }
            return total;
        }

        @Override
        public void hookOnRequest(LatticeReceiver terminal, long demand) {
            if(start >= array.length) {
                complete();
                return;
            }

            long end = start + demand;

            int count = 0;
            while (start < end && start < array.length) {
                terminal.push(array[start++]);
                count++;
            }
            addAndGetDemand(-count);
            VarHandle.releaseFence();
            if (start >= array.length) {
                complete();
            }
        }
    }
}
