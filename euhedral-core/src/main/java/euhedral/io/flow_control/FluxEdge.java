package euhedral.io.flow_control;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.io.flow_control.UpstreamQueue.UpstreamHandle;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.DrainBuffer;
import java.util.Collection;
import java.util.Iterator;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import lombok.Setter;
import org.jctools.maps.NonBlockingHashMapLong;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

@SuppressWarnings("unused")
public class FluxEdge extends UpstreamHandle implements Publisher<AbstractFrame>,
        Subscriber<AbstractFrame>, AutoCloseable {

    protected final AtomicReference<FluxEdge> parent = new AtomicReference<>(null);
    public final AtomicReference<Subscriber<? super AbstractFrame>> downstream = new AtomicReference<>(
            null);

    protected final AtomicBoolean drain;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean addingUpstream = new AtomicBoolean(false);

    protected final NonBlockingHashMapLong<UpstreamQueue> aggregators = new NonBlockingHashMapLong<>();
    private final WeakHashMap<UpstreamHandle, Boolean> upstreamHandles = new WeakHashMap<>();
    private final AtomicReference<Collection<UpstreamQueue>> upstreamQueues = new AtomicReference<>(aggregators.values());
    private final PaddedAtomicLong upstreamCount = new PaddedAtomicLong(0);
    private final PaddedAtomicLong threadCount = new PaddedAtomicLong(0);
    @Getter
    @Setter
    protected volatile FluxEdge sibling = null;

    public FluxEdge(AtomicBoolean drain) {
        this.drain = drain;
    }

    public UpstreamQueue getThreadUpstreamQueue() {
        FluxEdge parent = this.parent.getOpaque();
        if (parent != null) {
            return parent.getThreadUpstreamQueue();
        }

        UpstreamQueue queue = UpstreamQueue.UP_QUEUE.get();

        if (queue == null) {
            queue = UpstreamQueue.get(this.aggregators, this.threadCount);
            while (!this.addingUpstream.compareAndSet(false, true)) {
                Thread.onSpinWait();
            }

            Iterator<UpstreamHandle> iter = this.upstreamHandles.keySet().iterator();
            while (iter.hasNext()) {
                UpstreamHandle handle = iter.next();
                if (handle.isComplete()) {
                    iter.remove();
                    continue;
                }
                queue.addUpstream(handle);
            }
            queue.getTrueUpstreamCount();
            this.addingUpstream.lazySet(false);
        }
        return queue;
    }

    public void removeThread(Thread thread) {
        if (thread == null) {
            return;
        }

        FluxEdge parent = this.parent.getOpaque();
        if (parent != null) {
            parent.removeThread(thread);
            return;
        }

        var queue = this.aggregators.remove(thread.getId());
        if (queue != null) {
            this.threadCount.decrementAndGet();
            Collection<UpstreamQueue> observed = aggregators.values();
            this.upstreamQueues.compareAndSet(observed, aggregators.values());
        }
    }

    public long getHash() {
        return 0;
    }

    public long getUpstreamCount() {
        FluxEdge parent = this.parent.getOpaque();
        if (parent != null) {
            return parent.getUpstreamCount();
        }
        return this.upstreamCount.getOpaque();
    }

    public int getLayerWidth() {
        if (this.sibling == null) {
            return 1;
        }
        int count = 1;
        FluxEdge sib = this.sibling;
        while (sib != this) {
            count++;
            sib = sib.sibling;
        }
        return count;
    }

    public void setParent(FluxEdge parent) {
        if (parent == null) {
            this.parent.set(null);
            return;
        }

        acquireLock();
        this.parent.set(parent);
        transferToParent();
        parent.transferToParent();
        releaseLock();
    }

    @Override
    public void onNext(AbstractFrame frame) {
        Subscriber<? super AbstractFrame> downstream =
                this.downstream.getOpaque();
        if (downstream != null) {
            downstream.onNext(frame);
        }
    }

    @Override
    public void request(long num) {
        if (num < 0) {
            return;
        }
        if (this.closed.getOpaque() || this.drain.getOpaque()) {
            return;
        }

        FluxEdge parent = this.parent.getOpaque();
        if (parent != null) {
            parent.request(num);
            return;
        }

        UpstreamQueue queue = UpstreamQueue.get(this.aggregators, this.threadCount);
        queue.pull(num);
    }

    @Override
    public void pull(DrainBuffer buffer, long demand) {
        if (this.closed.getOpaque() || this.drain.getOpaque()) {
            return;
        }

        FluxEdge parent = this.parent.getOpaque();
        if (parent != null) {
            parent.pull(buffer, demand);
            return;
        }
        UpstreamQueue queue = UpstreamQueue.get(this.aggregators, this.threadCount);
        queue.pull(buffer, demand);
    }

    private void transferToParent() {
        FluxEdge parent = this.parent.getOpaque();
        if (parent == null) {
            return;
        }
        parent.aggregators.putAll(this.aggregators);
        parent.upstreamCount.addAndGet(this.upstreamCount.get());
        parent.threadCount.addAndGet(this.threadCount.get());
        parent.upstreamHandles.putAll(this.upstreamHandles);
        this.upstreamHandles.clear();
        this.aggregators.clear();
    }

    @Override
    public boolean isComplete() {
        return false;
    }

    @Override
    public void cancel() {
        close();
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        Subscriber<? super AbstractFrame> downstream = this.downstream.get();
        if (downstream != null) {
            downstream.onComplete();
            this.downstream.lazySet(null);
        }
        this.aggregators.clear();
        this.sibling = null;
        this.upstreamQueues.lazySet(null);
        this.upstreamHandles.clear();
    }

    @Override
    public void subscribe(Subscriber<? super AbstractFrame> subscriber) {
        boolean isEdge = subscriber instanceof FluxEdge;

        Subscriber<? super AbstractFrame> witness = this.downstream.compareAndExchange(null,
                subscriber);

        if (witness == null) {
            if (isEdge) {
                ((FluxEdge) subscriber).setParent(this);
            } else {
                subscriber.onSubscribe(this);
            }
            return;
        }

        if (witness instanceof FluxEdge existingEdge) {
            existingEdge.subscribe(subscriber);
        } else {
            subscriber.onError(
                    new IllegalStateException("Already subscribed by a terminal subscriber"));
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        if (subscription instanceof FluxEdge dh) {
            setParent(dh);
        } else if (subscription instanceof UpstreamHandle upstream) {
            if (this.closed.getOpaque()) {
                subscription.cancel();
                return;
            }
            acquireLock();

            FluxEdge parent = this.parent.get();
            if (parent != null) {
                releaseLock();
                parent.onSubscribe(upstream);
                return;
            }
            if (this.threadCount.get() <= 0) {
                releaseLock();
                subscription.cancel();
                return;
            }

            try {
                int cycles = 0;
                while (this.drain.getOpaque()) {
                    if (cycles++ < 128) {
                        Thread.onSpinWait();
                    } else if (cycles < 512) {
                        Thread.yield();
                    } else {
                        cycles = 0;
                        LockSupport.parkNanos(1_000);
                    }
                }

                this.upstreamHandles.put(upstream, true);

                Collection<UpstreamQueue> queues = this.upstreamQueues.get();
                while (queues.size() != this.threadCount.get()) {
                    if(this.upstreamQueues.compareAndSet(queues, this.aggregators.values())) {
                        break;
                    }
                    queues = this.aggregators.values();
                }
                for (var queue : queues) {
                    queue.addUpstream(upstream);
                }
                this.upstreamCount.incrementAndGet();
            } finally {
                releaseLock();
            }
        } else {
            subscription.cancel();
        }
    }

    private void acquireLock() {
        int cycles = 0;
        while (!this.addingUpstream.compareAndSet(false, true)) {
            if (cycles++ < 128) {
                Thread.onSpinWait();
            } else if (cycles < 512) {
                Thread.yield();
            } else {
                cycles = 0;
                LockSupport.parkNanos(1_000);
            }
        }
    }

    private void releaseLock() {
        this.addingUpstream.set(false);
    }

    public boolean isClosed() {
        return this.closed.getAcquire();
    }

    @Override
    public void onError(Throwable throwable) {
        Subscriber<? super AbstractFrame> downstream =
                this.downstream.getAcquire();
        if (downstream != null) {
            downstream.onError(throwable);
        }
    }

    @Override
    public void onComplete() {
        close();
    }
}
