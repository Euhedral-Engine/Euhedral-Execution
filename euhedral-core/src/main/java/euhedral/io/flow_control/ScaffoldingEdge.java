package euhedral.io.flow_control;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.io.flow_control.UpstreamQueue.UpstreamHandle;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.RecursiveScaffolding;
import euhedral.io.generics.ScaffoldingTerminal;
import euhedral.io.utils.DrainBuffer;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.Iterator;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import lombok.Setter;
import org.jctools.maps.NonBlockingHashMapLong;

@SuppressWarnings("unused")
public class ScaffoldingEdge extends UpstreamHandle implements AutoCloseable {

    protected static final VarHandle ADDING_UPSTREAM;
    protected static final VarHandle CLOSED;
    protected static final VarHandle DOWNSTREAM;
    protected static final VarHandle PARENT;
    protected static final VarHandle UP_QUEUES;

    static {
        try {
            ADDING_UPSTREAM = MethodHandles.lookup()
                    .findVarHandle(ScaffoldingEdge.class, "addingUpstream", boolean.class);
            CLOSED = MethodHandles.lookup()
                    .findVarHandle(ScaffoldingEdge.class, "closed", boolean.class);
            DOWNSTREAM = MethodHandles.lookup()
                    .findVarHandle(ScaffoldingEdge.class, "downstream", ScaffoldingTerminal.class);
            PARENT = MethodHandles.lookup()
                    .findVarHandle(ScaffoldingEdge.class, "parent", ScaffoldingEdge.class);
            UP_QUEUES = MethodHandles.lookup()
                    .findVarHandle(ScaffoldingEdge.class, "upstreamQueues", Collection.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }


    protected final AtomicBoolean drain;

    protected final NonBlockingHashMapLong<UpstreamQueue> aggregators = new NonBlockingHashMapLong<>();
    private final WeakHashMap<UpstreamHandle, Boolean> upstreamHandles = new WeakHashMap<>();
    private final PaddedAtomicLong upstreamCount = new PaddedAtomicLong(0);
    private final AtomicLong threadCount = new AtomicLong(0);
    public ScaffoldingTerminal downstream = null;
    protected ScaffoldingEdge parent = null;
    @Getter
    @Setter
    protected volatile ScaffoldingEdge sibling = null;

    private Collection<UpstreamQueue> upstreamQueues = aggregators.values();
    private boolean addingUpstream = false;
    private boolean closed = false;

    public ScaffoldingEdge(AtomicBoolean drain) {
        this.drain = drain;
    }

    public void register() {
        getThreadUpstreamQueue();
    }

    public UpstreamQueue getThreadUpstreamQueue() {
        ScaffoldingEdge parent = (ScaffoldingEdge) PARENT.getOpaque(this);
        if (parent != null) {
            return parent.getThreadUpstreamQueue();
        }

        UpstreamQueue queue = UpstreamQueue.UP_QUEUE.get();

        if (queue == null) {
            queue = UpstreamQueue.get(this.aggregators, this.threadCount);
            while (!ADDING_UPSTREAM.compareAndSet(this, false, true)) {
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
            ADDING_UPSTREAM.setRelease(this, false);
        }
        return queue;
    }

    public void removeThread(Thread thread) {
        if (thread == null) {
            return;
        }

        ScaffoldingEdge parent = (ScaffoldingEdge) PARENT.getOpaque(this);
        if (parent != null) {
            parent.removeThread(thread);
            return;
        }

        var queue = this.aggregators.remove(thread.getId());
        if (queue != null) {
            this.threadCount.decrementAndGet();
            Collection<UpstreamQueue> observed = aggregators.values();
            UP_QUEUES.compareAndSet(this, observed, aggregators.values());
        }
    }

    public long getUpstreamCount() {
        ScaffoldingEdge parent = (ScaffoldingEdge) PARENT.getOpaque(this);
        if (parent != null) {
            return parent.getUpstreamCount();
        }
        return this.upstreamCount.getOpaque();
    }

    public int getThreadCount() {
        return this.threadCount.intValue();
    }

    public int getLayerWidth() {
        if (this.sibling == null) {
            return 1;
        }
        int count = 1;
        ScaffoldingEdge sib = this.sibling;
        while (sib != this) {
            count++;
            sib = sib.sibling;
        }
        return count;
    }

    public void setParent(ScaffoldingEdge parent) {
        if (parent == null) {
            PARENT.setRelease(this, null);
            return;
        }

        acquireLock();
        PARENT.setRelease(this, parent);
        ScaffoldingTerminal down = (ScaffoldingTerminal) DOWNSTREAM.getOpaque(this);
        if (down instanceof ScaffoldingEdge rs) {
            rs.setParent(parent);
        }
        transferToParent();
        parent.transferToParent();
        releaseLock();
    }

    @Override
    public void onNext(AbstractFrame frame) {
        var downstream = (ScaffoldingTerminal) DOWNSTREAM.getOpaque(this);
        if (downstream != null) {
            downstream.onNext(frame);
        }
    }

    @Override
    public void request(long num) {
        if (num < 0) {
            return;
        }
        if ((boolean) CLOSED.getOpaque(this) || this.drain.getOpaque()) {
            return;
        }

        ScaffoldingEdge parent = (ScaffoldingEdge) PARENT.getOpaque(this);
        if (parent != null) {
            parent.request(num);
            return;
        }

        UpstreamQueue queue = UpstreamQueue.get(this.aggregators, this.threadCount);
        queue.pull(num);
    }

    @Override
    public void pull(DrainBuffer buffer, long demand) {
        if ((boolean) CLOSED.getOpaque(this) || this.drain.getOpaque()) {
            return;
        }

        ScaffoldingEdge parent = (ScaffoldingEdge) PARENT.getOpaque(this);
        if (parent != null) {
            parent.pull(buffer, demand);
            return;
        }
        UpstreamQueue queue = UpstreamQueue.get(this.aggregators, this.threadCount);
        queue.pull(buffer, demand);
    }

    protected void transferToParent() {
        ScaffoldingEdge parent = (ScaffoldingEdge) PARENT.getOpaque(this);
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
    public void close() {
        if (!CLOSED.compareAndSet(this, false, true)) {
            return;
        }

        var downstream = (ScaffoldingTerminal) DOWNSTREAM.getAcquire(this);
        if (downstream != null) {
            downstream.onComplete();
            DOWNSTREAM.setRelease(this, null);
        }
        this.aggregators.clear();
        this.sibling = null;
        UP_QUEUES.setRelease(this, null);
        this.upstreamHandles.clear();
    }

    @Override
    public void addUpstream(RecursiveScaffolding up) {
        if (up instanceof ScaffoldingEdge dh) {
            setParent(dh);
        } else if (up instanceof UpstreamHandle upstream) {
            acquireLock();

            ScaffoldingEdge parent = (ScaffoldingEdge) PARENT.getOpaque(this);
            if (parent != null) {
                releaseLock();
                parent.addUpstream(upstream);
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

                Collection<UpstreamQueue> queues = this.aggregators.values();
                while (queues.size() != this.threadCount.get()) {
                    if (UP_QUEUES.compareAndSet(this, queues, this.aggregators.values())) {
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
        }
    }

    @Override
    public void addDownstream(RecursiveScaffolding downstream) {
        boolean isEdge = downstream instanceof ScaffoldingEdge;

        var witness =
                (ScaffoldingTerminal) DOWNSTREAM.compareAndExchange(this, null,
                        downstream);

        if (witness == null) {
            if (isEdge) {
                ((ScaffoldingEdge) downstream).setParent(this);
            } else {
                downstream.addUpstream(this);
            }
            return;
        }

        if (witness instanceof ScaffoldingEdge existingEdge) {
            existingEdge.addDownstream(downstream);
        } else {
            downstream.onError(
                    new IllegalStateException(
                            "Already added as an upstream by a terminal downstream"));
        }
    }

    public void addDownstream(ScaffoldingTerminal terminal) {
        ScaffoldingTerminal down = (ScaffoldingTerminal) DOWNSTREAM.compareAndExchange(this, null,
                terminal);
        if (down == null) {
            return;
        }
        if (down instanceof RecursiveScaffolding rs) {
            rs.addDownstream(terminal);
        }
        terminal.onError(
                new IllegalStateException("Already added as an upstream by a terminal downstream"));
    }

    private void acquireLock() {
        int cycles = 0;
        while (!ADDING_UPSTREAM.compareAndSet(this, false, true)) {
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
        ADDING_UPSTREAM.setRelease(this, false);
    }

    @Override
    public void onError(Throwable throwable) {
        ScaffoldingTerminal downstream = (ScaffoldingTerminal) DOWNSTREAM.getAcquire(this);
        if (downstream != null) {
            downstream.onError(throwable);
        }
    }
}
