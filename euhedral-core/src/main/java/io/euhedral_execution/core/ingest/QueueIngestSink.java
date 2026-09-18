package io.euhedral_execution.core.ingest;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import io.euhedral_execution.data_structures.queues.common.ConcurrentPartitionedQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.NonNull;

/// Wraps a partitioned queue to allow it to be fed into the
/// [ControlPlaneLattice][io.euhedral_execution.core.control_plane.ControlPlaneLattice]
@SuppressWarnings("unused")
public sealed class QueueIngestSink extends AbstractIngestSink permits PipelineRunner {

    private final Delegate delegate;
    // Serializes publication with close, never frame checkout or user notifications.
    protected final Object lifecycleLock = new Object();
    private boolean publicationClosed;

    public QueueIngestSink() {
        this(new PartitionedMpscQueue<>(8_192));
    }

    public QueueIngestSink(@NonNull ConcurrentPartitionedQueue<AbstractFrame> queue) {
        Objects.requireNonNull(queue);
        this.delegate = new Delegate(queue, this);
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
        synchronized (this.lifecycleLock) {
            return !this.publicationClosed && this.delegate.queue.offer(frame);
        }
    }

    /// Offers the object to a random partition based on the seed. If the seed does not change, the
    /// same partition will be picked. Always succeeds if the queue is unbounded.
    ///
    /// @return success
    public boolean offer(long randomSeed, AbstractFrame frame) {
        Objects.requireNonNull(frame);
        synchronized (this.lifecycleLock) {
            return !this.publicationClosed && this.delegate.queue.offer(randomSeed, frame);
        }
    }

    /// Offers the object to a specific partition. Always succeeds if the queue is unbounded.
    ///
    /// @return success
    public boolean offer(int partition, AbstractFrame frame) {
        Objects.requireNonNull(frame);
        synchronized (this.lifecycleLock) {
            return !this.publicationClosed && this.delegate.queue.offer(partition, frame);
        }
    }

    /// Clears the queue.
    public void clear() {
        this.delegate.queue.clear();
    }

    /// Acquires the queue's single-consumer ownership without blocking a running drain.
    protected final void discardQueued(Consumer<AbstractFrame> consumer) {
        if (!this.delegate.draining.compareAndSet(false, true)) return;
        try {
            this.delegate.queue.drain(consumer, Long.MAX_VALUE);
        } finally {
            this.delegate.draining.set(false);
        }
    }

    protected void onDrainFinished() {}

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
        synchronized (this.lifecycleLock) {
            this.publicationClosed = true;
        }
        this.delegate.finishCompletion();
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
        final AtomicBoolean draining = new AtomicBoolean();
        // A request is a wakeup, not merely outstanding demand: an empty queue must not spin.
        final AtomicBoolean requestPending = new AtomicBoolean();

        boolean finish = false;

        private final QueueIngestSink owner;

        protected Delegate(ConcurrentPartitionedQueue<AbstractFrame> queue, QueueIngestSink owner) {
            this.queue = queue;
            this.owner = owner;
        }

        @Override
        public void complete() {
            this.owner.complete();
        }

        private void finishCompletion() {
            super.complete();
        }

        @Override
        public long hookOnPull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            if (!this.draining.compareAndSet(false, true)) return 0;
            long count;
            try {
                count = this.queue.drain(consumer, stopCondition, demand);
            } finally {
                this.draining.set(false);
                this.owner.onDrainFinished();
                drainRequests();
            }
            if (count == 0 && (boolean) FINISH.getAcquire(this) && this.queue.isEmpty()) {
                this.owner.complete();
            }
            return count;
        }

        @Override
        public void hookOnRequest(LatticeReceiver terminal, long demand) {
            this.requestPending.set(true);
            drainRequests();
        }

        private void drainRequests() {
            // Publish the wakeup before acquiring ownership. The releasing owner either sees it,
            // or the requester acquires ownership itself; callback requests never recurse.
            while (this.requestPending.get() && this.draining.compareAndSet(false, true)) {
                try {
                    this.requestPending.set(false);
                    var terminal = getDownstream();
                    if (isComplete() || terminal == null) return;
                    long count = this.queue.drain(terminal::push, this.demand.getAcquire());
                    // Account before releasing ownership so the next owner cannot over-deliver.
                    if (count > 0) {
                        addAndGetDemand(-count);
                    } else if ((boolean) FINISH.getAcquire(this) && this.queue.isEmpty()) {
                        this.owner.complete();
                    }
                } finally {
                    this.draining.set(false);
                    this.owner.onDrainFinished();
                }
            }
        }

        public void completeGracefully() {
            FINISH.setRelease(this, true);
        }
    }
}
