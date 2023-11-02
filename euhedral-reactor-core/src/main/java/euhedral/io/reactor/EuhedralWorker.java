package euhedral.io.reactor;

import euhedral.atomics.PaddedAtomicLong;
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

public class EuhedralWorker implements IngestSink, Worker {

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
        close();
    }

    @Override
    public boolean isDisposed() {
        return this.delegate.isClosed();
    }

    @Override
    public void close() {
        this.delegate.close();
    }

    private static class Delegate implements IngestSink.Delegate {

        private static final VarHandle CLOSED;
        private static final VarHandle TERMINAL;

        static {
            try {
                CLOSED = MethodHandles.lookup()
                        .findVarHandle(Delegate.class, "closed", boolean.class);
                TERMINAL = MethodHandles.lookup()
                        .findVarHandle(Delegate.class, "terminal", ScaffoldingTerminal.class);
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        private static long accumulate(long curr, long next) {
            if (curr + next < 0) {
                return Long.MAX_VALUE;
            }
            return curr + next;
        }

        private final PartitionedUnboundedMpscArrayQueue<TaskFrame> queue;
        private final PaddedAtomicLong demand = new PaddedAtomicLong(0);

        private ScaffoldingTerminal terminal;
        private boolean closed;

        Delegate(int chunkSize, int maxPooledChunks) {
            this.queue = new PartitionedUnboundedMpscArrayQueue<>(1, chunkSize, maxPooledChunks);
        }

        @Override
        public void request(long demand) {
            boolean closed = (boolean) CLOSED.getOpaque(this);
            var terminal = (ScaffoldingTerminal) TERMINAL.getOpaque(this);

            if (closed || terminal == null || demand <= 0) {
                return;
            }

            demand = this.demand.accumulateAndGet(demand, Delegate::accumulate);
            int batch = (int) Math.min(demand, Integer.MAX_VALUE);

            int count = this.queue.drain(0, this::drain, batch);
            if (count > 0) {
                this.demand.getAndAdd(-count);
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
        public void cancel() {
            close();
        }

        @Override
        public void addDownstream(ScaffoldingTerminal downstream) {
            if (!TERMINAL.compareAndSet(this, null, terminal)) {
                terminal.onError(new IllegalStateException("Already Subscribed"));
            }
            terminal.addUpstream(this);
        }

        public boolean isClosed() {
            return (boolean) CLOSED.getOpaque(this);
        }

        @Override
        public void close() {
            if (CLOSED.compareAndSet(this, false, true)) {
                var t = (ScaffoldingTerminal) TERMINAL.getAndSet(this, null);
                this.demand.lazySet(0);
                if (t != null) {
                    t.onComplete();
                }
            }
        }
    }
}
