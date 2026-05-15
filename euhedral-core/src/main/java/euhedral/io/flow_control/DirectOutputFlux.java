package euhedral.io.flow_control;

import euhedral.io.frames.AbstractFrame;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import euhedral.queues.common.PartitionedQueue;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

@SuppressWarnings("unchecked")
public class DirectOutputFlux implements Publisher<AbstractFrame>, Subscription {

    protected static final VarHandle CANCELLED;
    protected static final VarHandle SUBSCRIBER;
    protected static final VarHandle UNLIMITED;

    static {
        try {
            CANCELLED = MethodHandles.lookup()
                    .findVarHandle(DirectOutputFlux.class, "cancelled", boolean.class);
            SUBSCRIBER = MethodHandles.lookup()
                    .findVarHandle(DirectOutputFlux.class, "subscriber", Subscriber.class);
            UNLIMITED = MethodHandles.lookup()
                    .findVarHandle(DirectOutputFlux.class, "unlimited", boolean.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected final AtomicLong demand = new AtomicLong(0);

    protected final PartitionedQueue<AbstractFrame> buffer;
    protected final Consumer<AbstractFrame> applyToEach;

    protected boolean unlimited = false;
    protected boolean cancelled = false;
    protected Subscriber<? super AbstractFrame> subscriber = null;


    public DirectOutputFlux(@NonNull PartitionedQueue<AbstractFrame> buffer,
            Consumer<AbstractFrame> applyToEach) {
        this.buffer = buffer;
        this.applyToEach = applyToEach;
    }

    public int drain(long max) {
        if (max == 0 || SUBSCRIBER.getOpaque(this) == null || (boolean) CANCELLED.getOpaque(this)) {
            return 0;
        }

        boolean unlimited = (boolean) UNLIMITED.getOpaque(this);
        long currentDemand = unlimited ? Long.MAX_VALUE : this.demand.getAcquire();
        if (currentDemand <= 0) {
            return 0;
        }

        int limit = (int) Math.min(max, currentDemand);

        int drain = this.buffer.drain(this::drainInternal, limit);

        if (!unlimited) {
            this.demand.addAndGet(-drain);
        }
        return drain;
    }

    private void drainInternal(AbstractFrame frame) {
        if (this.applyToEach != null) {
            this.applyToEach.accept(frame);
        }
        Subscriber<? super AbstractFrame> subscriber = (Subscriber<? super AbstractFrame>) SUBSCRIBER.getOpaque(
                this);
        if (subscriber != null) {
            subscriber.onNext(frame);
        }
    }

    public boolean isEmpty() {
        return this.buffer.isEmpty();
    }

    @Override
    public void subscribe(Subscriber<? super AbstractFrame> subscriber) {
        if (!CANCELLED.compareAndSet(this, true, false) && !SUBSCRIBER.compareAndSet(this, null,
                subscriber)) {
            subscriber.onError(new IllegalAccessException("This class already has a subscriber"));
            return;
        }
        Subscriber<? super AbstractFrame> observed = (Subscriber<? super AbstractFrame>) SUBSCRIBER.getOpaque(
                this);
        if (!SUBSCRIBER.compareAndSet(this, observed, subscriber)) {
            subscriber.onError(new IllegalAccessException("This class already has a subscriber"));
            return;
        }
        subscriber.onSubscribe(this);
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
        SUBSCRIBER.setVolatile(this, null);
    }
}
