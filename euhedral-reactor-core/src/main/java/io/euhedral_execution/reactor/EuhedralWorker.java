package io.euhedral_execution.reactor;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.ingest.AbstractIngestSink;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.reactor.common.TaskFrame;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler.Worker;

@SuppressWarnings("unused")
public final class EuhedralWorker extends AbstractIngestSink implements Worker {

    static EuhedralWorker spawn(int chunkSize, int maxPooledChunks) {
        return new EuhedralWorker(chunkSize, maxPooledChunks);
    }

    private final Delegate delegate;
    private final long idHash;

    EuhedralWorker(int chunkSize, int maxPooledChunks) {
        this.delegate = new Delegate(chunkSize, maxPooledChunks);
        this.idHash = HasherApi.mix(ThreadLocalRandom.current().nextLong());
    }

    public void submit(TaskFrame frame) {
        while(!this.delegate.queue.offer(frame)) {
            Thread.onSpinWait();
        }
    }

    @Override
    public @NonNull Disposable schedule(@NonNull Runnable task) {
        Objects.requireNonNull(task);
        return TaskFrame.create(this.idHash, task, this, 0, 0, TimeUnit.NANOSECONDS);
    }

    @Override
    public @NonNull Disposable schedule(@NonNull Runnable task, long delay, @NonNull TimeUnit unit) {
        Objects.requireNonNull(task);
        Objects.requireNonNull(unit);
        return schedulePeriodically(task, delay, 0, unit);
    }

    @Override
    public @NonNull Disposable schedulePeriodically(@NonNull Runnable task, long delay, long period, @NonNull TimeUnit unit) {
        Objects.requireNonNull(task);
        Objects.requireNonNull(unit);
        return TaskFrame.create(this.idHash, task, this, delay, period, unit);
    }

    @Override
    public LatticeSource getDelegate() {
        return this.delegate;
    }

    @Override
    public void dispose() {
        complete();
    }

    @Override
    public boolean isDisposed() {
        return this.delegate.isComplete();
    }

    @Override
    public void complete() {
        this.delegate.complete();
    }

    private static class Delegate extends AbstractIngestSink.Delegate {

        private static final VarHandle COMPLETE;

        static {
            try {
                COMPLETE = MethodHandles.lookup()
                        .findVarHandle(Delegate.class, "complete", boolean.class);
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        private final PartitionedMpscQueue<TaskFrame> queue;

        private boolean complete;

        Delegate(int chunkSize, int maxPooledChunks) {
            this.queue = new PartitionedMpscQueue<>(1, chunkSize, maxPooledChunks);
        }

        @Override
        public long hookOnPull(Consumer<AbstractFrame> consumer, long demand) {
            return 0;
        }

        @Override
        public void hookOnRequest(LatticeReceiver terminal, long demand) {
            if (complete) {
                return;
            }

            long count = this.queue.drain(0, this::drain, demand);
            if (count > 0) {
                addAndGetDemand(-count);
            }
        }

        private void drain(TaskFrame frame) {
            LatticeReceiver terminal = this.terminal;
            if (terminal == null) {
                terminal = (LatticeReceiver) TERMINAL.getOpaque(this);
            }

            if (terminal == null) {
                return;
            }
            terminal.push(frame);
        }

        @Override
        public void addDownstream(LatticeReceiver downstream) {
            if (!TERMINAL.compareAndSet(this, null, terminal)) {
                terminal.onError(new IllegalStateException("Already Subscribed"));
            }
            terminal.addUpstream(this);
        }

        public boolean isComplete() {
            return (boolean) COMPLETE.getOpaque(this);
        }

        @Override
        public void complete() {
            if (COMPLETE.compareAndSet(this, false, true)) {
                var t = (LatticeReceiver) TERMINAL.getAndSet(this, null);
                this.demand.lazySet(0);
                if (t != null) {
                    t.onComplete();
                }
            }
        }
    }
}
