package euhedral.io.reactor;

import euhedral.hashing.HasherApi;
import euhedral.io.generics.IngestSink;
import euhedral.io.generics.ScaffoldingSource;
import euhedral.io.generics.ScaffoldingTerminal;
import euhedral.io.reactor.common.TaskFrame;
import euhedral.queues.PartitionedUnboundedMpscArrayQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler.Worker;

@SuppressWarnings("unused")
public class EuhedralWorker extends IngestSink implements Worker {

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
    public ScaffoldingSource getDelegate() {
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

    private static class Delegate extends IngestSink.Delegate {

        private static final VarHandle COMPLETE;

        static {
            try {
                COMPLETE = MethodHandles.lookup()
                        .findVarHandle(Delegate.class, "complete", boolean.class);
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        private final PartitionedUnboundedMpscArrayQueue<TaskFrame> queue;

        private boolean complete;

        Delegate(int chunkSize, int maxPooledChunks) {
            this.queue = new PartitionedUnboundedMpscArrayQueue<>(1, chunkSize, maxPooledChunks);
        }

        @Override
        public void hookOnRequest(ScaffoldingTerminal terminal, long demand) {
            if (complete) {
                return;
            }

            long count = this.queue.drain(0, this::drain, demand);
            if (count > 0) {
                addAndGetDemand(-count);
            }
        }

        private void drain(TaskFrame frame) {
            ScaffoldingTerminal terminal = this.terminal;
            if (terminal == null) {
                terminal = (ScaffoldingTerminal) TERMINAL.getOpaque(this);
            }

            if (terminal == null) {
                return;
            }
            terminal.onNext(frame);
        }

        @Override
        public void addDownstream(ScaffoldingTerminal downstream) {
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
                var t = (ScaffoldingTerminal) TERMINAL.getAndSet(this, null);
                this.demand.lazySet(0);
                if (t != null) {
                    t.onComplete();
                }
            }
        }
    }
}
