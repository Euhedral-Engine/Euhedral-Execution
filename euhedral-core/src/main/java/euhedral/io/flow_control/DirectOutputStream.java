package euhedral.io.flow_control;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.ScaffoldingOrigin;
import euhedral.io.interfaces.ScaffoldingTerminal;
import euhedral.queues.common.PartitionedQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

public class DirectOutputStream implements ScaffoldingOrigin {

    protected static final VarHandle CANCELLED;
    protected static final VarHandle TERMINAL;
    protected static final VarHandle UNLIMITED;

    static {
        try {
            CANCELLED = MethodHandles.lookup()
                    .findVarHandle(DirectOutputStream.class, "cancelled", boolean.class);
            TERMINAL = MethodHandles.lookup()
                    .findVarHandle(DirectOutputStream.class, "terminal", ScaffoldingTerminal.class);
            UNLIMITED = MethodHandles.lookup()
                    .findVarHandle(DirectOutputStream.class, "unlimited", boolean.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected final AtomicLong demand = new AtomicLong(0);

    protected final PartitionedQueue<AbstractFrame> buffer;
    protected final Consumer<AbstractFrame> applyToEach;

    protected boolean unlimited = false;
    protected boolean cancelled = false;
    protected ScaffoldingTerminal terminal = null;


    public DirectOutputStream(@NonNull PartitionedQueue<AbstractFrame> buffer,
            Consumer<AbstractFrame> applyToEach) {
        this.buffer = buffer;
        this.applyToEach = applyToEach;
    }

    public int push(long max) {
        if (max == 0 || TERMINAL.getOpaque(this) == null || (boolean) CANCELLED.getOpaque(this)) {
            return 0;
        }

        boolean unlimited = (boolean) UNLIMITED.getOpaque(this);
        long currentDemand = unlimited ? Long.MAX_VALUE : this.demand.getAcquire();
        if (currentDemand <= 0) {
            return 0;
        }

        int limit = (int) Math.min(max, currentDemand);

        int drain = this.buffer.drain(this::pushInternal, limit);

        if (!unlimited) {
            this.demand.addAndGet(-drain);
        }
        return drain;
    }

    private void pushInternal(AbstractFrame frame) {
        if (this.applyToEach != null) {
            this.applyToEach.accept(frame);
        }
        ScaffoldingTerminal subscriber = (ScaffoldingTerminal) TERMINAL.getOpaque(this);
        if (subscriber != null) {
            subscriber.onNext(frame);
        }
    }

    public boolean isEmpty() {
        return this.buffer.isEmpty();
    }

    @Override
    public void addDownstream(ScaffoldingTerminal terminal) {
        if (!CANCELLED.compareAndSet(this, true, false) && !TERMINAL.compareAndSet(this, null,
                terminal)) {
            terminal.onError(new IllegalAccessException("This class already has a terminal"));
            return;
        }
        ScaffoldingTerminal observed = (ScaffoldingTerminal) TERMINAL.getOpaque(this);
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
    public void cancel() {
        CANCELLED.setVolatile(this, true);
        TERMINAL.setVolatile(this, null);
    }
}
