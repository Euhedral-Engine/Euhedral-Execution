package euhedral.io.flow_control;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeReceiver;
import euhedral.io.generics.LatticeSource;
import io.euhedral_execution.data_structures.queues.common.BatchableQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

/// Used for pushing frames from one stage to the next. Assumes that only one thread will call push
/// at a time. This class can only have one downstream.
public class DirectOutputStream implements LatticeSource {

    protected static final VarHandle COMPLETE;
    protected static final VarHandle TERMINAL;
    protected static final VarHandle UNLIMITED;

    static {
        try {
            COMPLETE = MethodHandles.lookup()
                    .findVarHandle(DirectOutputStream.class, "complete", boolean.class);
            TERMINAL = MethodHandles.lookup()
                    .findVarHandle(DirectOutputStream.class, "terminal", LatticeReceiver.class);
            UNLIMITED = MethodHandles.lookup()
                    .findVarHandle(DirectOutputStream.class, "unlimited", boolean.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected final AtomicLong demand = new AtomicLong(0);

    protected final BatchableQueue<AbstractFrame> buffer;
    protected final Consumer<AbstractFrame> applyToEach;

    protected boolean unlimited = false;
    protected boolean complete = false;
    protected LatticeReceiver terminal = null;


    public DirectOutputStream(@NonNull BatchableQueue<AbstractFrame> buffer,
            Consumer<AbstractFrame> applyToEach) {
        this.buffer = buffer;
        this.applyToEach = applyToEach;
    }

    @Override
    public void pull(Consumer<AbstractFrame> consumer, long demand) {
        this.buffer.drain(consumer, demand);
    }

    /// Pushes the indicated number of frames to the next stage. Only safe to be called by one thread
    /// at a time.
    ///
    /// @param max Maximum number of frames to push
    public long push(long max) {
        if (max == 0 || TERMINAL.getOpaque(this) == null || (boolean) COMPLETE.getOpaque(this)) {
            return 0;
        }

        boolean unlimited = (boolean) UNLIMITED.getOpaque(this);
        long currentDemand = unlimited ? Long.MAX_VALUE : this.demand.getAcquire();
        if (currentDemand <= 0) {
            return 0;
        }

        long limit = Math.min(max, currentDemand);

        long drain = this.buffer.drain(this::pushInternal, limit);

        if (!unlimited) {
            this.demand.addAndGet(-drain);
        }
        return drain;
    }

    private void pushInternal(AbstractFrame frame) {
        if (this.applyToEach != null) {
            this.applyToEach.accept(frame);
        }
        LatticeReceiver subscriber = (LatticeReceiver) TERMINAL.getOpaque(this);
        if (subscriber != null) {
            subscriber.push(frame);
        }
    }

    public boolean isEmpty() {
        return this.buffer.isEmpty();
    }

    @Override
    public void addDownstream(LatticeReceiver terminal) {
        if (!COMPLETE.compareAndSet(this, true, false) && !TERMINAL.compareAndSet(this, null,
                terminal)) {
            terminal.onError(new IllegalAccessException("This class already has a terminal"));
            return;
        }
        LatticeReceiver observed = (LatticeReceiver) TERMINAL.getOpaque(this);
        if (!TERMINAL.compareAndSet(this, observed, terminal)) {
            terminal.onError(new IllegalAccessException("This class already has a terminal"));
            return;
        }
        terminal.addUpstream(this);
    }

    @Override
    public void request(long num) {
        if (num < 0) {
            throw new IllegalArgumentException("Cannot pass a negative request: " + num);
        }
        if ((boolean) UNLIMITED.getOpaque(this)) {
            return;
        }

        long temp = demand.addAndGet(num);
        if (temp < 0) {
            UNLIMITED.setRelease(this, true);
        }
    }

    @Override
    public void complete() {
        COMPLETE.setVolatile(this, true);
        TERMINAL.setVolatile(this, null);
    }
}
