package io.euhedral_execution.reactor.common;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.hashing.HasherApi;
import io.euhedral_execution.reactor.EuhedralWorker;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import reactor.core.Disposable;

public final class TaskFrame extends AbstractFrame implements Disposable {

    public static TaskFrame create(long idHash, Runnable task, EuhedralWorker sink, long delay, long period, TimeUnit unit) {
        return new TaskFrame(idHash, task, sink, delay, period, unit);
    }

    private final Runnable task;
    @Getter
    private final long periodNs;

    private Thread thread;
    private long seed;

    private TaskFrame(long idHash, Runnable task, EuhedralWorker sink, long delay, long period, @NonNull TimeUnit unit) {
        super(idHash, null, new AtomicBoolean());

        this.task = task;
        this.periodNs = unit.toNanos(period);
        this.seed = HasherApi.mix(idHash + 25); // What's funnier than 24?

        randomizeHash(this.seed++);

        LockSupport.parkNanos(unit.toNanos(delay));
        if(periodNs <= 0) {
            sink.submit(this);
        } else {
            CompletableFuture.runAsync(() -> {
                this.thread = Thread.currentThread();
                cycle(sink);
                kill();
            });
        }
    }

    private void cycle(EuhedralWorker sink) {
        while (!Thread.interrupted() && isAlive()) {
            if(sink.isDisposed()) {
                break;
            }

            randomizeHash(this.seed++);
            sink.submit(this);

            long now = System.nanoTime();
            // doFinally() will unpark
            LockSupport.park();
            long delta = System.nanoTime() - now;

            LockSupport.parkNanos(this.periodNs - delta);
        }
    }

    @Override
    public void execute() {
        this.task.run();
    }

    @Override
    public void dispose() {
        kill();
        if(this.thread != null) {
            this.thread.interrupt();
            LockSupport.unpark(this.thread);
        }
    }

    @Override
    public boolean isDisposed() {
        return !isAlive();
    }

    @Override
    public void doFinally() {
        if(this.thread != null) {
            LockSupport.unpark(this.thread);
        }
    }
}
