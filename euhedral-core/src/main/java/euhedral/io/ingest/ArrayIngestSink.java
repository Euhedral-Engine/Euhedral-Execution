package euhedral.io.ingest;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.IngestSink;
import euhedral.io.generics.LaticeSource;
import euhedral.io.generics.LatticeReceiver;
import java.lang.invoke.VarHandle;

/// Wraps an array to allow it to be ingested by the
/// [ControlPlane][euhedral.io.control_plane.ControlPlane]
@SuppressWarnings("unused")
public class ArrayIngestSink extends IngestSink {

    private final Delegate delegate;

    public ArrayIngestSink(AbstractFrame[] frames) {
        this.delegate = new Delegate(frames);
    }

    /// Returns the delegate the ControlPlane will use to ingest the array.
    public LaticeSource getDelegate() {
        return this.delegate;
    }

    /// Resets the sink to allow it to be ingested again. The sink must be passed back into the
    /// ControlPlane's ingest again.
    public void reset() {
        this.delegate.reset();
    }

    @Override
    public void complete() {
        delegate.complete();
    }

    protected static final class Delegate extends IngestSink.Delegate {

        private final AbstractFrame[] array;
        int start;

        public Delegate(AbstractFrame[] array) {
            this.array = array;
        }

        @Override
        public void hookOnRequest(LatticeReceiver terminal, long demand) {
            if(start >= array.length) {
                complete();
                return;
            }

            int batch = (int) Math.min(demand, Integer.MAX_VALUE);

            int count = 0;
            while (start < batch && start < array.length) {
                terminal.onNext(array[start++]);
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
