package euhedral.io.reactor.common;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.hashing.HasherApi;
import euhedral.io.frames.RunnableFrame;
import euhedral.io.impl.FrameFactory;
import euhedral.io.impl.FrameManager;
import euhedral.io.interfaces.IngestSink;
import euhedral.io.interfaces.ScaffoldingSource;
import euhedral.io.interfaces.ScaffoldingTerminal;
import euhedral.queues.PartitionedUnboundedMpscArrayQueue;
import reactor.core.Disposable;

public class TaskIngestSink implements IngestSink {

    private final Delegate delegate;
    private final long idHash;

    private long seed;

    public TaskIngestSink(int chunkSize, int maxPooledChunks) {
        this.delegate = new Delegate(chunkSize, maxPooledChunks);
        this.idHash = HasherApi.mix(ThreadLocalRandom.current().nextLong());
        this.seed = HasherApi.mix(ThreadLocalRandom.current().nextLong());
    }

    public void submit(Runnable task) {
        RunnableFrame frame = new RunnableFrame(idHash, task, null, null);
        frame.randomizeHash(HasherApi.mix(seed++));
        while (!this.delegate.queue.offer(frame)) {
            Thread.onSpinWait();
        }
    }

    public Disposable submit(Runnable task, long delay, long period, TimeUnit unit) {
        return new PeriodicRunner(task, delay, period, unit);
    }

    private void submit(RunnableFrame frame) {
        while (!this.delegate.queue.offer(frame)) {
            Thread.onSpinWait();
        }
    }

    @Override
    public ScaffoldingSource getDelegate() {
        return this.delegate;
    }

    @Override
    public void close() {
        this.delegate.close();
    }

    private class PeriodicRunner implements Disposable {

        private static final VarHandle DISPOSED;

        static {
            try {
                DISPOSED = MethodHandles.lookup()
                        .findVarHandle(PeriodicRunner.class, "disposed", boolean.class);
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        private boolean disposed;

        PeriodicRunner(Runnable task, long delay, long period, TimeUnit unit) {
            long delayNs = unit.toNanos(delay);
            long periodNs = unit.toNanos(period);
            long password = HasherApi.mix(ThreadLocalRandom.current().nextLong());

            CompletableFuture.runAsync(() -> {
                if (delay > 0) {
                    LockSupport.parkNanos(delayNs);
                }
                if (periodNs > 0) {
                    try (FrameManager<Void, RunnableFrame> frameManager = new FrameManager<>(64, 0,
                            password)) {
                        frameManager.setFactory(new FrameFactory<>(
                                (idHash, ignored) -> new RunnableFrame(idHash, task, null,
                                        frameManager), (ignored, frame) -> {
                        }));
                        long interval = unit.toNanos(periodNs);
                        while (!Thread.interrupted() && !(boolean) DISPOSED.getOpaque(this)) {
                            RunnableFrame frame = frameManager.get(password);
                            TaskIngestSink.this.submit(frame);
                            LockSupport.parkNanos(unit.toNanos(interval));
                        }
                    }
                } else {
                    TaskIngestSink.this.submit(task);
                }
            });
        }

        @Override
        public void dispose() {
            DISPOSED.setVolatile(this, true);
        }

        @Override
        public boolean isDisposed() {
            return (boolean) DISPOSED.getOpaque(this);
        }
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

        private final PartitionedUnboundedMpscArrayQueue<RunnableFrame> queue;
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

        private void drain(RunnableFrame frame) {
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
