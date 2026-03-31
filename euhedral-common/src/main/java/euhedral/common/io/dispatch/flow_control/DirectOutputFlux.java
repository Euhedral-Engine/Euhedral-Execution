package euhedral.common.io.dispatch.flow_control;

import euhedral.common.io.dispatch.frames.AbstractFrame;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MessagePassingQueue.WaitStrategy;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class DirectOutputFlux implements Publisher<AbstractFrame>, Subscription {

    private final AtomicLong demand = new AtomicLong(0);

    private final MessagePassingQueue<AbstractFrame> buffer;
    private final Consumer<AbstractFrame> applyToEach;

    private volatile boolean unlimited = false;
    private volatile boolean cancelled = false;
    private Subscriber<? super AbstractFrame> subscriber;


    public DirectOutputFlux(@NonNull MessagePassingQueue<AbstractFrame> buffer,
            Consumer<AbstractFrame> applyToEach) {
        this.buffer = buffer;
        this.applyToEach = applyToEach;
    }

    public int drain(int max) {
        if (max == 0 || subscriber == null || cancelled) {
            return 0;
        }

        long currentDemand = unlimited ? Long.MAX_VALUE : demand.get();
        if (currentDemand <= 0) {
            return 0;
        }

        int limit = (int) Math.min(max, currentDemand);


        int drain = buffer.drain(this::drainInternal, limit);

        if (!unlimited) {
            this.demand.addAndGet(-drain);
        }
        return drain;
    }

    private void drainInternal(AbstractFrame frame) {
        if (applyToEach != null) {
            applyToEach.accept(frame);
        }

        subscriber.onNext(frame);
    }

    public boolean isEmpty() {
        return buffer.isEmpty();
    }

    @Override
    public void subscribe(Subscriber<? super AbstractFrame> subscriber) {
        if (this.subscriber != null && !cancelled) {
            subscriber.onError(new IllegalAccessException("This class already has a subscriber"));
        }
        this.subscriber = subscriber;
        this.cancelled = false;
        subscriber.onSubscribe(this);
    }

    @Override
    public void request(long num) {
        if (num < 0) {
            throw new IllegalArgumentException("Cannot pass a negative request: " + num);
        }
        if (unlimited) {
            return;
        }

        long temp = demand.addAndGet(num);
        if (temp < 0) {
            unlimited = true;
        }
    }

    @Override
    public void cancel() {
        cancelled = true;
        subscriber = null;
    }
}
