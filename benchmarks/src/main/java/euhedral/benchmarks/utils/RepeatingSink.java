package euhedral.benchmarks.utils;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeReceiver;
import euhedral.io.generics.LatticeSource;
import euhedral.io.ingest.AbstractIngestSink;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.function.Consumer;

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

    protected static final class Delegate extends AbstractIngestSink.Delegate {

        private final AbstractFrame[] array;
        int start;

        public Delegate(AbstractFrame[] array) {
            this.array = array;
        }

        @Override
        public long hookOnPull(Consumer<AbstractFrame> consumer, long demand) {
            long total = 0;
            while (total < demand) {
                AbstractFrame frame = this.array[this.start++];
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
            int total = 0;
            while (total < demand) {
                AbstractFrame frame = this.array[this.start++];
                Objects.requireNonNull(frame);
                terminal.push(frame);
                total++;
                this.start %= this.array.length;
            }
            addAndGetDemand(-total);
            VarHandle.releaseFence();
        }
    }
}
