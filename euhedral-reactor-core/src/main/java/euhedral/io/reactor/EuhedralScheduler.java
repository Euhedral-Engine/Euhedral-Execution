package euhedral.io.reactor;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import euhedral.io.reactor.common.TaskIngestSink;
import euhedral.io.utils.MathFunctions;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@SuppressWarnings("resource")
public class EuhedralScheduler implements Scheduler {
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final TaskIngestSink[] sinks;

    public EuhedralScheduler() {
        this.sinks = new TaskIngestSink[Runtime.getRuntime().availableProcessors()];
        for (int i = 0; i < this.sinks.length; i++) {
            this.sinks[i] = new TaskIngestSink(4096, 2);
        }
    }

    @Override
    public Disposable schedule(Runnable task) {
        return null;
    }

    @Override
    public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
        return getSink().submit(task, delay, 0, unit);
    }

    @Override
    public Disposable schedulePeriodically(Runnable task, long initialDelay, long period,
            TimeUnit unit) {
        return getSink().submit(task, initialDelay, period, unit);
    }

    @Override
    public long now(TimeUnit unit) {
        return unit.convert(System.nanoTime(), TimeUnit.NANOSECONDS);
    }

    @Override
    public Worker createWorker() {
        return new EuhedralWorker(getSink());
    }

    @Override
    public void dispose() {
        if (this.disposed.compareAndSet(false, true)) {
            for(TaskIngestSink sink : sinks) {
                sink.close();
            }
        }
    }

    @Override
    public boolean isDisposed() {
        return this.disposed.getOpaque();
    }

    @Override
    public Mono<Void> disposeGracefully() {
        if(this.disposed.compareAndSet(false, true)) {
            return Mono.fromRunnable(this::dispose);
        }
        return Mono.empty();
    }

    @Override
    public void init() {
        Scheduler.super.init();
    }

    private TaskIngestSink getSink() {
        long rand = ThreadLocalRandom.current().nextLong();
        int idx = (int) MathFunctions.unsignedMultiplyHigh(rand, this.sinks.length);
        return this.sinks[idx];
    }
}
