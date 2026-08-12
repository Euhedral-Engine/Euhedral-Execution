package io.euhedral_execution.core.ingest;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import io.euhedral_execution.data_structures.queues.common.ConcurrentPartitionedQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;

/// Wraps a partitioned queue to allow it to be fed into the
/// [ControlPlaneLattice][io.euhedral_execution.core.control_plane.ControlPlaneLattice]
@SuppressWarnings("unused")
public sealed class QueueIngestSink extends AbstractIngestSink permits PipelineRunner {

    private final Delegate delegate;

    public QueueIngestSink() {
        this(new PartitionedMpscQueue<>(8_192));
    }

    public QueueIngestSink(@NonNull ConcurrentPartitionedQueue<AbstractFrame> queue) {
        Objects.requireNonNull(queue);
        this.delegate = new Delegate(queue);
    }

    @Override
    public @NonNull final LatticeSource getDelegate() {
        if (isComplete()) {
            throw new IllegalAccessError("Cannot get delegate from completed ingest sink");
        }
        return this.delegate;
    }

    /// Offers the object to each partition starting from 0 until it finds room. Always succeeds if the queue unbounded.
    ///
    /// @return success
    public boolean offer(AbstractFrame frame) {
        Objects.requireNonNull(frame);
        if (isComplete()) {
            throw new IllegalAccessError("Cannot offer frames to a completed ingest sink");
        }
        return this.delegate.queue.offer(frame);
    }

    /// Offers the object to a random partition based on the seed. If the seed does not change, the
    /// same partition will be picked. Always succeeds if the queue is unbounded.
    ///
    /// @return success
    public boolean offer(long randomSeed, AbstractFrame frame) {
        Objects.requireNonNull(frame);
        if (isComplete()) {
            throw new IllegalAccessError("Cannot offer frames to a completed ingest sink");
        }
        return this.delegate.queue.offer(randomSeed, frame);
    }

    /// Offers the object to a specific partition. Always succeeds if the queue is unbounded.
    ///
    /// @return success
    public boolean offer(int partition, AbstractFrame frame) {
        Objects.requireNonNull(frame);
        if (isComplete()) {
            throw new IllegalAccessError("Cannot offer frames to a completed ingest sink");
        }
        return this.delegate.queue.offer(partition, frame);
    }

    /// Clears the queue.
    public void clear() {
        this.delegate.queue.clear();
    }

    public long size() {
        return this.delegate.queue.sizeLong();
    }

    public long getDemand() {
        return this.delegate.demand.getAcquire();
    }

    /// Disconnects from the [ControlPlaneLattice][io.euhedral_execution.core.control_plane.ControlPlaneLattice]
    /// immediately. Does not clear the queue.
    @Override
    public void complete() {
        this.delegate.complete();
    }

    @Override
    public boolean isComplete() {
        return this.delegate.isComplete();
    }

    /// Disconnects from the [ControlPlaneLattice][io.euhedral_execution.core.control_plane.ControlPlaneLattice] when
    /// the queue is finished being drained.
    public void completeGracefully() {
        this.delegate.completeGracefully();
    }

    protected static final class Delegate extends AbstractIngestSink.Delegate {

        static final VarHandle FINISH;

        static {
            try {
                FINISH = MethodHandles.lookup().findVarHandle(Delegate.class, "finish", boolean.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        final ConcurrentPartitionedQueue<AbstractFrame> queue;

        boolean finish = false;

        protected Delegate(ConcurrentPartitionedQueue<AbstractFrame> queue) {
            this.queue = queue;
        }

        @Override
        public long hookOnPull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            long count = this.queue.drain(consumer, stopCondition, demand);
            if (count == 0 && (boolean) FINISH.getOpaque(this)) {
                super.complete();
            }
            return count;
        }

        @Override
        public void hookOnRequest(LatticeReceiver terminal, long demand) {
            long count = this.queue.drain(terminal::push, demand);
            if (count > 0) {
                addAndGetDemand(-count);
            } else if ((boolean) FINISH.getOpaque(this)) {
                super.complete();
            }
        }

        public void completeGracefully() {
            FINISH.setRelease(this, true);
        }
    }
}
