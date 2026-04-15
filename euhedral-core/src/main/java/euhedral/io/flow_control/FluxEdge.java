package euhedral.io.flow_control;

import euhedral.io.flow_control.UpstreamQueue.UpstreamHandle;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.DrainBuffer;
import java.util.Collection;
import java.util.Iterator;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import jdk.internal.vm.annotation.Contended;
import lombok.Getter;
import lombok.Setter;
import org.jctools.maps.NonBlockingHashMapLong;
import org.jctools.util.PaddedAtomicLong;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

@Contended
public class FluxEdge extends UpstreamHandle implements Publisher<AbstractFrame>,
        Subscriber<AbstractFrame>, AutoCloseable {

    protected final NonBlockingHashMapLong<UpstreamQueue> aggregators = new NonBlockingHashMapLong<>();

    private final AtomicBoolean addingUpstream = new AtomicBoolean(false);
    private final WeakHashMap<UpstreamHandle, Boolean> upstreamHandles = new WeakHashMap<>();

    protected final AtomicBoolean drain;
    private final PaddedAtomicLong upstreamCount = new PaddedAtomicLong(0);
    private final PaddedAtomicLong threadCount = new PaddedAtomicLong(0);
    public volatile Subscriber<? super AbstractFrame> downstream = null;
    protected volatile FluxEdge parent = null;

    @Getter
    @Setter
    @Contended("sibling")
    protected volatile FluxEdge sibling = null;
    @Getter
    @Setter
    protected volatile int index = 0;

    private volatile Collection<UpstreamQueue> upstreamQueues = aggregators.values();
    @Getter
    private volatile boolean closed = false;

    public FluxEdge(AtomicBoolean drain) {
        this.drain = drain;
    }

    public UpstreamQueue getThreadUpstreamQueue() {
        if (parent != null) {
            return parent.getThreadUpstreamQueue();
        }

        UpstreamQueue queue = UpstreamQueue.UP_QUEUE.get();

        if(queue == null) {
            queue = UpstreamQueue.get(aggregators, threadCount);
            while(!addingUpstream.compareAndSet(false, true)) {
                Thread.onSpinWait();
            }

            Iterator<UpstreamHandle> iter = upstreamHandles.keySet().iterator();
            while(iter.hasNext()) {
                UpstreamHandle handle = iter.next();
                if(handle.isComplete()) {
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
            this.parent = null;
            return;
        }

        acquireLock();
        this.parent = parent;
        transferToParent();
        releaseLock();
        this.parent.transferToParent();
    }

    @Override
    public void onNext(AbstractFrame frame) {
        if (downstream != null) {
            downstream.onNext(frame);
        }
    }

    @Override
    public void request(long num) {
        if (num < 0) {
            return;
        }
        if (closed || drain.get()) {
            return;
        }

        FluxEdge parent = this.parent;
        if (parent != null) {
            parent.request(num);
            return;
        }

        UpstreamQueue queue = UpstreamQueue.get(aggregators, threadCount);
        queue.pull(num);
    }

    @Override
    public void pull(DrainBuffer buffer, long demand) {
        if(closed || drain.get()) {
            return;
        }

        FluxEdge parent = this.parent;
        if (parent != null) {
            parent.pull(buffer, demand);
            return;
        }
        UpstreamQueue queue = UpstreamQueue.get(aggregators, threadCount);
        queue.pull(buffer, demand);
    }

    private void transferToParent() {
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
        if (closed) {
            return;
        }
        closed = true;
        if (downstream != null) {
            downstream.onComplete();
        }
        aggregators.clear();
    }

    @Override
    public void subscribe(Subscriber<? super AbstractFrame> subscriber) {
        if (subscriber instanceof FluxEdge dh) {
            if (this.downstream == null) {
                this.downstream = dh;
                dh.setParent(this);
            } else if (this.downstream instanceof FluxEdge child) {
                child.subscribe(subscriber);
            } else {
                subscriber.onError(new IllegalAccessException(
                        "This class cannot have multiple subscribers"));
            }
        } else if (this.downstream == null) {
            this.downstream = subscriber;
            subscriber.onSubscribe(this);
        } else {
            subscriber.onError(new IllegalAccessException(
                    "This FluxEdge is already subscribed to."));
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        if (subscription instanceof FluxEdge dh) {
            setParent(dh);
        } else if (subscription instanceof UpstreamHandle upstream) {
            if (closed) {
                subscription.cancel();
                return;
            }
            acquireLock();
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
            if(cycles++ < 128) {
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

    @Override
    public void onError(Throwable throwable) {
        if (downstream != null) {
            downstream.onError(throwable);
        }
    }

    @Override
    public void onComplete() {
        close();
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof FluxEdge e) {
            return e.index == index;
        }
        return false;
    }
}
