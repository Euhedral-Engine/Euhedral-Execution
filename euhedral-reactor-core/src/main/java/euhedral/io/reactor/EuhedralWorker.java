package euhedral.io.reactor;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.TimeUnit;

import euhedral.io.reactor.common.TaskIngestSink;
import reactor.core.Disposable;
import reactor.core.scheduler.Scheduler.Worker;

public class EuhedralWorker implements Worker {
    private static final VarHandle DISPOSED;

    static {
        try {
            DISPOSED = MethodHandles.lookup()
                    .findVarHandle(EuhedralWorker.class, "disposed", boolean.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private boolean disposed;
    private final TaskIngestSink sink;

    private EuhedralWorker() {
        this.sink = null;
    }

    EuhedralWorker(TaskIngestSink sink) {
        this.sink = sink;
    }

    @Override
    public void dispose() {
        DISPOSED.setVolatile(this, true);
    }

    @Override
    public boolean isDisposed() {
        return (boolean) DISPOSED.getOpaque(this);
    }

    @Override
    public Disposable schedule(Runnable task) {
        return this.sink.submit(task, 0, 0, TimeUnit.NANOSECONDS);
    }

    @Override
    public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
        return this.sink.submit(task, delay, 0, unit);
    }

    @Override
    public Disposable schedulePeriodically(Runnable task, long initialDelay, long period,
            TimeUnit unit) {
        return this.sink.submit(task, initialDelay, period, unit);
    }
}
