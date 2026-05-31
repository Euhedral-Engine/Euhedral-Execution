package euhedral.io.ingest;

import euhedral.io.control_plane.ControlPlaneLattice;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeReceiver;
import euhedral.io.generics.LatticeSource;
import euhedral.queues.PartitionedUnboundedMpmcArrayQueue;
import euhedral.queues.common.ConcurrentPartitionedQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.function.Consumer;

/// Wraps a partitioned queue to allow it to be fed into the
/// [ControlPlaneLattice][ControlPlaneLattice]
@SuppressWarnings("unused")
public final class QueueIngestSink extends AbstractIngestSink {

    private final Delegate delegate;

    public QueueIngestSink() {
        this(new PartitionedUnboundedMpmcArrayQueue<>(16_384));
    }

    public QueueIngestSink(ConcurrentPartitionedQueue<AbstractFrame> queue) {
        this.delegate = new Delegate(queue);
    }

    @Override
    public LatticeSource getDelegate() {
        return null;
    }

    /// Offers the object to each partition starting from 0 until it succeeds.
    ///
    /// @return success
    public boolean offer(AbstractFrame frame) {
        return this.delegate.queue.offer(frame);
    }

    /// Offers the object to a random partition based on the seed. If the seed does not change, the
    /// same partition will be picked.
    ///
    /// @return success
    public boolean offer(long randomSeed, AbstractFrame frame) {
        return this.delegate.queue.offer(randomSeed, frame);
    }

    /// Offers the object to a specific partition
    ///
    /// @return success
    public boolean offer(int partition, AbstractFrame frame) {
        return this.delegate.queue.offer(partition, frame);
    }

    /// Clears the queue.
    public void clear() {
        this.delegate.queue.clear();
    }

    /// Disconnects from the [ControlPlaneLattice] immediately. Does not clear
    /// the queue.
    @Override
    public void complete() {
        this.delegate.complete();
    }

    /// Disconnects from the [ControlPlaneLattice] when the queue is finished being drained.
    public void gracefulComplete() {
        this.delegate.gracefulComplete();
    }

    protected static final class Delegate extends AbstractIngestSink.Delegate {
        static final VarHandle FINISH;

        static {
            try {
                FINISH = MethodHandles.lookup().findVarHandle(Delegate.class, "finish", boolean.class);
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        final ConcurrentPartitionedQueue<AbstractFrame> queue;

        boolean finish = false;

        protected Delegate(ConcurrentPartitionedQueue<AbstractFrame> queue) {
            this.queue = queue;
        }

        @Override
        public void hookOnPull(Consumer<AbstractFrame> consumer, long demand) {
            long count = this.queue.drain(consumer, demand);
            if(count == 0 && (boolean) FINISH.getOpaque(this)) {
                complete();
            }
        }

        @Override
        public void hookOnRequest(LatticeReceiver terminal, long demand) {
            long count = this.queue.drain(terminal::push, demand);
            if (count > 0) {
                addAndGetDemand(-count);
            } else if((boolean) FINISH.getOpaque(this)) {
                complete();
            }
        }

        public void gracefulComplete() {
            FINISH.setRelease(this, true);
        }
    }
}
