package euhedral.io.flow_control;

import euhedral.io.frames.AbstractFrame;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jctools.queues.MessagePassingQueue;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class DirectOutputFlux implements Publisher<AbstractFrame>, Subscription {

    protected final AtomicLong demand = new AtomicLong(0);

    protected final MessagePassingQueue<AbstractFrame> buffer;
    protected final Consumer<AbstractFrame> applyToEach;

    protected final AtomicBoolean unlimited = new AtomicBoolean(false);
    protected final AtomicBoolean cancelled = new AtomicBoolean(false);
    protected final AtomicReference<Subscriber<? super AbstractFrame>> subscriber = new AtomicReference<>(
            null);


    public DirectOutputFlux(@NonNull MessagePassingQueue<AbstractFrame> buffer,
            Consumer<AbstractFrame> applyToEach) {
        this.buffer = buffer;
        this.applyToEach = applyToEach;
    }

    public int drain(long max) {
        if (max == 0 || this.subscriber.getOpaque() == null || this.cancelled.getOpaque()) {
            return 0;
        }

        long currentDemand = this.unlimited.getOpaque() ? Long.MAX_VALUE : this.demand.getAcquire();
        if (currentDemand <= 0) {
            return 0;
        }

        int limit = (int) Math.min(max, currentDemand);

        int drain = this.buffer.drain(this::drainInternal, limit);

        if (!this.unlimited.getOpaque()) {
            this.demand.addAndGet(-drain);
        }
        return drain;
    }

    private void drainInternal(AbstractFrame frame) {
        if (this.applyToEach != null) {
            this.applyToEach.accept(frame);
        }
        Subscriber<? super AbstractFrame> subscriber = this.subscriber.getOpaque();
        if (subscriber != null) {
            subscriber.onNext(frame);
        }
    }

    public boolean isEmpty() {
        return this.buffer.isEmpty();
    }

    @Override
    public void subscribe(Subscriber<? super AbstractFrame> subscriber) {
        if (!this.cancelled.compareAndSet(true, false) || !this.subscriber.compareAndSet(null,
                subscriber)) {
            subscriber.onError(new IllegalAccessException("This class already has a subscriber"));
            return;
        }
        Subscriber<? super AbstractFrame> observed = this.subscriber.get();
        if (!this.subscriber.compareAndSet(observed, subscriber)) {
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
        if (this.unlimited.getOpaque()) {
            return;
        }

        long temp = demand.addAndGet(num);
        if (temp < 0) {
            this.unlimited.setRelease(true);
        }
    }

    @Override
    public void cancel() {
        this.subscriber.set(null);
        this.cancelled.set(true);
    }
}
