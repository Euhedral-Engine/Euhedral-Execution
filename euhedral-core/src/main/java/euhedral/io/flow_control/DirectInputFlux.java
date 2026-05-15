package euhedral.io.flow_control;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.io.frames.AbstractFrame;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.SpscUnboundedArrayQueue;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

@SuppressWarnings("unused")
public class DirectInputFlux implements Publisher<AbstractFrame>, Subscription {

    private static long addCap(long num1, long num2) {
        long sum = num1 + num2;
        if (sum < 0) {
            return Long.MAX_VALUE;
        }
        return sum;
    }

    private final PaddedAtomicLong wip = new PaddedAtomicLong(0);
    private final PaddedAtomicLong demand = new PaddedAtomicLong(0);
    private final AtomicLong bufferCount = new AtomicLong(0);
    private final SpscUnboundedArrayQueue<AbstractFrame> buffer;
    private final AtomicReference<Subscriber<? super AbstractFrame>> downstream = new AtomicReference<>(
            null);

    public DirectInputFlux(int chunkSize) {
        int cap = Integer.highestOneBit((chunkSize - 1) << 1);
        this.buffer = new SpscUnboundedArrayQueue<>(cap);
    }

    public long getDemand() {
        return this.demand.getAcquire();
    }

    public boolean isEmpty() {
        return this.bufferCount.getAcquire() == 0;
    }

    @Override
    public void subscribe(Subscriber<? super AbstractFrame> subscriber) {
        if (this.downstream.compareAndSet(null, subscriber)) {
            subscriber.onSubscribe(this);
        } else {
            subscriber.onError(new IllegalStateException("This class can only have 1 subscriber"));
        }
    }

    @Override
    public void request(long demand) {
        if (demand <= 0) {
            return;
        }

        drain(this.demand.accumulateAndGet(demand, DirectInputFlux::addCap));
    }

    public void drain() {
        Subscriber<? super AbstractFrame> down = this.downstream.getOpaque();
        if (down != null) {
            drain(this.demand.getAcquire());
        }
    }

    private void drain(long demand) {
        if (wip.compareAndSet(0, 1)) {
            try {
                long total = 0;
                int count;
                int batch = (int) Math.min(demand, Integer.MAX_VALUE);
                while ((count = this.buffer.drain(this::drain, batch)) != 0) {
                    demand -= count;
                    total += count;
                    Thread.onSpinWait();
                }
                if (total > 0) {
                    this.demand.accumulateAndGet(-total, DirectInputFlux::addCap);
                    this.bufferCount.addAndGet(-total);
                }
            } finally {
                wip.lazySet(0);
            }
        }
    }

    private void drain(AbstractFrame frame) {
        Subscriber<? super AbstractFrame> down = this.downstream.getOpaque();
        if (down != null) {
            down.onNext(frame);
        }
    }

    public long getBufferCount() {
        return this.bufferCount.getAcquire();
    }

    public void fill(MessagePassingQueue<AbstractFrame> frames) {
        if (frames == null) {
            return;
        }

        int count = frames.drain(this::add);
        this.bufferCount.addAndGet(count);
    }

    private void add(AbstractFrame frame) {
        while (!this.buffer.relaxedOffer(frame)) {
            Thread.onSpinWait();
        }
    }

    public long enqueue(Collection<AbstractFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            return this.bufferCount.getAcquire();
        }

        this.buffer.addAll(frames);
        return this.bufferCount.addAndGet(frames.size());
    }

    public long enqueue(AbstractFrame frame) {
        while (!this.buffer.relaxedOffer(frame)) {
            Thread.onSpinWait();
        }
        return this.bufferCount.incrementAndGet();
    }

    @Override
    public void cancel() {
        Subscriber<? super AbstractFrame> down = this.downstream.getAcquire();
        if (down != null) {
            down.onComplete();
            this.downstream.setRelease(null);
        }
    }
}
