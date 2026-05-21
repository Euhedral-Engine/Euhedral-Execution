package euhedral.io.reactor.common;

import euhedral.hashing.HasherApi;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.reactor.EuhedralWorker;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import reactor.core.Disposable;

public class TaskFrame extends AbstractFrame implements Disposable {

    private static final VarHandle DISPOSED;

    static {
        try {
            DISPOSED = MethodHandles.lookup()
                    .findVarHandle(TaskFrame.class, "disposed", boolean.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    public static TaskFrame build(long idHash, Runnable task, EuhedralWorker sink, long delay, long period, TimeUnit unit) {
        return new TaskFrame(idHash, task, sink, delay, period, unit);
    }

    private final Runnable task;
    @Getter
    private final long periodNs;

    private boolean disposed;
    private Thread thread;
    private long seed = HasherApi.mix(ThreadLocalRandom.current().nextLong());

    public TaskFrame(long idHash, Runnable task, EuhedralWorker sink, long delay, long period, TimeUnit unit) {
        super(idHash, null);

        this.task = task;
        this.periodNs = unit.toNanos(period);

        randomizeHash(this.seed++);

        long delayNs = unit.toNanos(delay);

        if(delayNs <= 0 && periodNs <= 0) {
            sink.submit(this);
        } else {
            CompletableFuture.runAsync(() -> {
                this.thread = Thread.currentThread();
                if (delay > 0) {
                    LockSupport.parkNanos(delayNs);
                }
                if (periodNs > 0) {
                    while (!Thread.interrupted() && !(boolean) DISPOSED.getOpaque(this)) {
                        if(sink.isDisposed()) {
                            break;
                        }

                        sink.submit(this);

                        long now = System.nanoTime();
                        LockSupport.park();

                        long delta = System.nanoTime() - now;
                        delta = periodNs - delta;
                        if(delta > 0) {
                            LockSupport.parkNanos(delta);
                        }
                        randomizeHash(this.seed++);
                    }
                    DISPOSED.setRelease(this, true);
                } else {
                    sink.submit(this);
                }
            });
        }
    }

    @Override
    public long getSizeBytes() {
        return 64;
    }

    @Override
    public void execute() {
        this.task.run();
    }

    @Override
    public boolean isAlive() {
        return !isDisposed();
    }

    @Override
    public void kill() {
        dispose();
    }

    @Override
    public void dispose() {
        DISPOSED.setVolatile(this, true);
        if(this.thread != null) {
            this.thread.interrupt();
            LockSupport.unpark(this.thread);
        }
    }

    @Override
    public boolean isDisposed() {
        return (boolean) DISPOSED.getOpaque(this);
    }

    @Override
    public void doFinally() {
        if(this.thread != null) {
            LockSupport.unpark(this.thread);
        }
    }
}
