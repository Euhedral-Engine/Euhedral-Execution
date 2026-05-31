package euhedral.io.ingest;

import euhedral.io.control_plane.ControlPlaneLattice;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeReceiver;
import euhedral.io.generics.LatticeSource;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;

/// Wraps an array to allow it to be ingested by the
/// [ControlPlaneLattice][ControlPlaneLattice]
@SuppressWarnings("unused")
public class ArrayIngestSink extends AbstractIngestSink {

    private final Delegate delegate;

    public ArrayIngestSink(AbstractFrame[] frames) {
        this.delegate = new Delegate(frames);
    }

    /// Returns the delegate the ControlPlaneLattice will use to ingest the array.
    public LatticeSource getDelegate() {
        return this.delegate;
    }

    /// Resets the sink to allow it to be ingested again. The sink must be passed back into the
    /// ControlPlaneLattice's ingest again.
    public void reset() {
        this.delegate.reset();
    }

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
        public void hookOnPull(Consumer<AbstractFrame> consumer, long demand) {
            if(start >= array.length) {
                complete();
                return;
            }

            long end = start + demand;

            while (start < end && start < array.length) {
                consumer.accept(array[start++]);
            }
            VarHandle.releaseFence();
            if (start >= array.length) {
                complete();
            }
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

        @Override
        public void reset() {
            super.reset();
            this.start = 0;
            VarHandle.releaseFence();
        }
    }
}
