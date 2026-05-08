package euhedral.io.flow_control;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.io.flow_control.UpstreamQueue.UpstreamHandle;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.DrainBuffer;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.Iterator;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import lombok.Setter;
import org.jctools.maps.NonBlockingHashMapLong;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

@SuppressWarnings("unchecked")
public class FluxEdge extends UpstreamHandle implements Publisher<AbstractFrame>,
        Subscriber<AbstractFrame>, AutoCloseable {

    protected static final VarHandle PARENT;
    protected static final VarHandle DOWNSTREAM;

    static {
        try {
            PARENT = MethodHandles.lookup().findVarHandle(FluxEdge.class, "parent", FluxEdge.class);
            DOWNSTREAM = MethodHandles.lookup().findVarHandle(FluxEdge.class, "downstream",
                    Subscriber.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    protected final AtomicBoolean drain;
    protected final NonBlockingHashMapLong<UpstreamQueue> aggregators = new NonBlockingHashMapLong<>();
    private final AtomicBoolean addingUpstream = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean();

    private final WeakHashMap<UpstreamHandle, Boolean> upstreamHandles = new WeakHashMap<>();
    private final PaddedAtomicLong upstreamCount = new PaddedAtomicLong(0);
    private final PaddedAtomicLong threadCount = new PaddedAtomicLong(0);
    @Getter
    @Setter
    protected volatile FluxEdge sibling = null;
    private volatile Collection<UpstreamQueue> upstreamQueues = aggregators.values();

    protected FluxEdge parent = null;
    public Subscriber<? super AbstractFrame> downstream = null;

    public FluxEdge(AtomicBoolean drain) {
        this.drain = drain;
    }

    public UpstreamQueue getThreadUpstreamQueue() {
        FluxEdge parent = (FluxEdge) PARENT.getAcquire(this);
        if (parent != null) {
            return parent.getThreadUpstreamQueue();
        }

        UpstreamQueue queue = UpstreamQueue.UP_QUEUE.get();

        if (queue == null) {
            queue = UpstreamQueue.get(aggregators, threadCount);
            while (!addingUpstream.compareAndSet(false, true)) {
                Thread.onSpinWait();
            }

            Iterator<UpstreamHandle> iter = upstreamHandles.keySet().iterator();
            while (iter.hasNext()) {
                UpstreamHandle handle = iter.next();
                if (handle.isComplete()) {
                    iter.remove();
                    continue;
                }
                queue.addUpstream(handle);
            }
            queue.getTrueUpstreamCount();
            addingUpstream.lazySet(false);
        }
        return queue;
    }

    public void removeThread(Thread thread) {
        if (thread == null) {
            return;
        }

        FluxEdge parent = (FluxEdge) PARENT.getAcquire(this);
        if (parent != null) {
            parent.removeThread(thread);
            return;
        }

        var queue = aggregators.remove(thread.getId());
        if (queue != null) {
            threadCount.decrementAndGet();
            upstreamQueues = aggregators.values();
        }
    }

    public long getHash() {
        return 0;
    }

    public long getUpstreamCount() {
        FluxEdge parent = (FluxEdge) PARENT.getAcquire(this);
        if (parent != null) {
            return parent.getUpstreamCount();
        }
        return upstreamCount.get();
    }

    public int getLayerWidth() {
        if (sibling == null) {
            return 1;
        }
        int count = 1;
        FluxEdge sib = sibling;
        while (sib != this) {
            count++;
            sib = sib.sibling;
        }
        return count;
    }

    public int countLeafNodes() {
        return 1;
    }

    public void setParent(FluxEdge parent) {
        if (parent == null) {
            PARENT.setRelease(this, null);
            return;
        }

        acquireLock();
        PARENT.setRelease(this, parent);
        transferToParent();
        parent.transferToParent();
        releaseLock();
    }

    @Override
    public void onNext(AbstractFrame frame) {
        Subscriber<? super AbstractFrame> downstream =
                (Subscriber<? super AbstractFrame>) DOWNSTREAM.getAcquire(this);
        if (downstream != null) {
            downstream.onNext(frame);
        }
    }

    @Override
    public void request(long num) {
        if (num < 0) {
            return;
        }
        if (closed.getAcquire() || drain.getAcquire()) {
            return;
        }

        FluxEdge parent = (FluxEdge) PARENT.getAcquire(this);
        if (parent != null) {
            parent.request(num);
            return;
        }

        UpstreamQueue queue = UpstreamQueue.get(aggregators, threadCount);
        queue.pull(num);
    }

    @Override
    public void pull(DrainBuffer buffer, long demand) {
        if (closed.getAcquire() || drain.getAcquire()) {
            return;
        }

        FluxEdge parent = (FluxEdge) PARENT.getAcquire(this);
        if (parent != null) {
            parent.pull(buffer, demand);
            return;
        }
        UpstreamQueue queue = UpstreamQueue.get(aggregators, threadCount);
        queue.pull(buffer, demand);
    }

    private void transferToParent() {
        FluxEdge parent = (FluxEdge) PARENT.getAcquire(this);
        if (parent == null) {
            return;
        }
        parent.aggregators.putAll(aggregators);
        parent.upstreamCount.addAndGet(upstreamCount.get());
        parent.threadCount.addAndGet(threadCount.get());
        parent.upstreamHandles.putAll(upstreamHandles);
        upstreamHandles.clear();
        aggregators.clear();
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
        if(!closed.compareAndSet(false, true)) {
            return;
        }

        Subscriber<? super AbstractFrame> downstream =
                (Subscriber<? super AbstractFrame>) DOWNSTREAM.getAcquire(this);
        if (downstream != null) {
            downstream.onComplete();
        }
        aggregators.clear();
    }

    @Override
    public void subscribe(Subscriber<? super AbstractFrame> subscriber) {
        boolean isEdge = subscriber instanceof FluxEdge;

        Subscriber<? super AbstractFrame> witness = (Subscriber<? super AbstractFrame>)
                DOWNSTREAM.compareAndExchange(this, null, subscriber);

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
            subscriber.onError(new IllegalStateException("Already subscribed by a terminal subscriber"));
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        if (subscription instanceof FluxEdge dh) {
            setParent(dh);
        } else if (subscription instanceof UpstreamHandle upstream) {
            if (closed.getAcquire()) {
                subscription.cancel();
                return;
            }
            acquireLock();

            FluxEdge parent = (FluxEdge) PARENT.getAcquire(this);
            if (parent != null) {
                releaseLock();
                parent.onSubscribe(upstream);
                return;
            }
            if (threadCount.get() <= 0) {
                releaseLock();
                subscription.cancel();
                return;
            }

            try {
                int cycles = 0;
                while (drain.get()) {
                    if (cycles++ < 128) {
                        Thread.onSpinWait();
                    } else if (cycles < 512) {
                        Thread.yield();
                    } else {
                        cycles = 0;
                        LockSupport.parkNanos(1_000);
                    }
                }

                upstreamHandles.put(upstream, true);

                if (upstreamQueues.size() != threadCount.get()) {
                    upstreamQueues = aggregators.values();
                }
                for (var queue : upstreamQueues) {
                    queue.addUpstream(upstream);
                }
                upstreamCount.incrementAndGet();
            } finally {
                releaseLock();
            }
        } else {
            subscription.cancel();
        }
    }

    private void acquireLock() {
        int cycles = 0;
        while (!addingUpstream.compareAndSet(false, true)) {
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
        addingUpstream.set(false);
    }

    public boolean isClosed() {
        return this.closed.getAcquire();
    }

    @Override
    public void onError(Throwable throwable) {
        Subscriber<? super AbstractFrame> downstream =
                (Subscriber<? super AbstractFrame>) DOWNSTREAM.getAcquire(this);
        if (downstream != null) {
            downstream.onError(throwable);
        }
    }

    @Override
    public void onComplete() {
        close();
    }
}
