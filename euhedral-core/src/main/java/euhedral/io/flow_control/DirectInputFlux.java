package euhedral.io.flow_control;

import euhedral.io.frames.AbstractFrame;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.Setter;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.SpscUnboundedArrayQueue;
import org.jctools.queues.unpadded.SpscUnboundedUnpaddedArrayQueue;
import org.jctools.util.PaddedAtomicLong;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class DirectInputFlux implements Publisher<AbstractFrame>, Subscription {

    private final PaddedAtomicLong wip = new PaddedAtomicLong(0);
    private final PaddedAtomicLong demand = new PaddedAtomicLong(0);

    private final AtomicLong bufferCount = new AtomicLong(0);
    private final SpscUnboundedArrayQueue<AbstractFrame> buffer;

    private volatile Subscriber<? super AbstractFrame> downstream;

    @Getter
    @Setter
    private boolean paused;

    public DirectInputFlux(int chunkSize) {
        int cap = Integer.highestOneBit((chunkSize - 1) << 1);
        this.buffer = new SpscUnboundedArrayQueue<>(cap);
    }

    public long getDemand() {
        return demand.get();
    }

    public boolean isEmpty() {
        return bufferCount.get() == 0;
    }

    @Override
    public void subscribe(Subscriber<? super AbstractFrame> subscriber) {
        if (this.downstream == null) {
            this.downstream = subscriber;
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
        downstream.onNext(frame);
    }

    private static long addCap(long num1, long num2) {
        long sum = num1 + num2;
        if (sum < 0) {
            return Long.MAX_VALUE;
        }
        return sum;
    }

    public long getBufferCount() {
        return bufferCount.get();
    }

    public void drain() {
        if (downstream != null) {
            drain(demand.get());
        }
    }

    public void fill(MessagePassingQueue<AbstractFrame> frames) {
        if (frames == null) {
            return;
        }

        int count = frames.drain(this::add);
        bufferCount.addAndGet(count);
    }

    private void add(AbstractFrame frame) {
        while (!buffer.relaxedOffer(frame)) {
            Thread.onSpinWait();
        }
    }

    public long enqueue(Collection<AbstractFrame> frames) {
        if (frames == null || frames.isEmpty()) {
            return bufferCount.get();
        }

        buffer.addAll(frames);
        return bufferCount.addAndGet(frames.size());
    }

    public long enqueue(AbstractFrame frame) {
        while (!buffer.relaxedOffer(frame)) {
            Thread.onSpinWait();
        }
        return bufferCount.incrementAndGet();
    }

    @Override
    public void cancel() {
        if (this.downstream != null) {
            this.downstream.onComplete();
        }
    }
}
