package euhedral.io.ingest;

import euhedral.io.control_plane.ControlPlaneLattice;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LaticeSource;
import euhedral.io.generics.LatticeReceiver;
import euhedral.queues.PartitionedUnboundedMpmcArrayQueue;
import euhedral.queues.common.ConcurrentPartitionedQueue;

/// Wraps a partitioned queue to allow it to be fed into the
/// [ControlPlaneLattice][ControlPlaneLattice]
@SuppressWarnings("unused")
public class QueueIngestSink extends IngestSink {

    private final Delegate delegate;

    public QueueIngestSink() {
        this(new PartitionedUnboundedMpmcArrayQueue<>(16_384));
    }

    public QueueIngestSink(ConcurrentPartitionedQueue<AbstractFrame> queue) {
        this.delegate = new Delegate(queue);
    }

    @Override
    public LaticeSource getDelegate() {
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

    /// Disconnects from the [ControlPlaneLattice][ControlPlaneLattice]. Does not clear
    /// the queue.
    @Override
    public void complete() {
        this.delegate.complete();
    }

    protected static final class Delegate extends IngestSink.Delegate {

        final ConcurrentPartitionedQueue<AbstractFrame> queue;

        protected Delegate(ConcurrentPartitionedQueue<AbstractFrame> queue) {
            this.queue = queue;
        }

        @Override
        public void hookOnRequest(LatticeReceiver terminal, long demand) {
            long count = this.queue.drain(terminal::onNext, demand);
            if (count > 0) {
                addAndGetDemand(-count);
            }
        }
    }
}
