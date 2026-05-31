package euhedral.io.flow_control;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.io.flow_control.UpstreamQueue.UpstreamHandle;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeInterceptor;
import euhedral.io.generics.LatticeReceiver;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Collection;
import java.util.Iterator;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import lombok.Getter;
import lombok.Setter;
import org.jctools.maps.NonBlockingHashMapLong;

/// ## The main infrastructure class of Euhedral Core
///
/// This is the structural backbone of the entire system. A `LatticeEdge` forms a dynamic execution
/// graph by recursively linking to other edges whenever it is attached upstream or downstream.
///
/// When another `LatticeEdge` is connected, it becomes part of the same chain, effectively
/// extending the execution topology. When an `UpstreamHandle` is added, it is propagated upward
/// through the graph. When a `LatticeReceiver` is attached, it becomes the execution boundary (the
/// “floor”) of that branch.
///
/// Work flows downward through the graph toward receivers, while demand and backpressure flow
/// upward toward upstream sources. The structure is designed to continuously reconcile both
/// directions under concurrent mutation.
@SuppressWarnings("unused")
public class LatticeEdge extends UpstreamHandle implements AutoCloseable {

    protected static final VarHandle ADDING_UPSTREAM;
    protected static final VarHandle CLOSED;
    protected static final VarHandle DOWNSTREAM;
    protected static final VarHandle PARENT;
    protected static final VarHandle UP_QUEUES;

    static {
        try {
            ADDING_UPSTREAM = MethodHandles.lookup()
                    .findVarHandle(LatticeEdge.class, "addingUpstream", boolean.class);
            CLOSED = MethodHandles.lookup()
                    .findVarHandle(LatticeEdge.class, "closed", boolean.class);
            DOWNSTREAM = MethodHandles.lookup()
                    .findVarHandle(LatticeEdge.class, "downstream", LatticeReceiver.class);
            PARENT = MethodHandles.lookup()
                    .findVarHandle(LatticeEdge.class, "parent", LatticeEdge.class);
            UP_QUEUES = MethodHandles.lookup()
                    .findVarHandle(LatticeEdge.class, "upstreamQueues", Collection.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }


    protected final AtomicBoolean drain;

    protected final NonBlockingHashMapLong<UpstreamQueue> aggregators =
            new NonBlockingHashMapLong<>();
    private final WeakHashMap<UpstreamHandle, Boolean> upstreamHandles = new WeakHashMap<>();
    private final PaddedAtomicLong upstreamCount = new PaddedAtomicLong(0);
    private final AtomicLong threadCount = new AtomicLong(0);
    public LatticeReceiver downstream = null;
    protected LatticeEdge parent = null;
    @Getter
    @Setter
    protected volatile LatticeEdge sibling = null;

    private Collection<UpstreamQueue> upstreamQueues = aggregators.values();
    private boolean addingUpstream = false;
    private boolean closed = false;

    public LatticeEdge(AtomicBoolean drain) {
        this.drain = drain;
    }

    /// Creates a thread-local [UpstreamQueue] object for the calling thread. This queue contains
    /// all [UpstreamHandles][UpstreamHandle] associated with the LatticeEdge.
    ///
    /// This does not need to be called to avoid errors. It is a micro-optimization.
    public void register() {
        getThreadUpstreamQueue();
    }

    /// Gets the thread-local [UpstreamQueue] object for the calling thread. This queue contains all
    /// [UpstreamHandles][UpstreamHandle] associated with the LatticeEdge.
    public UpstreamQueue getThreadUpstreamQueue() {
        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
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

    /// Removes a thread and its [UpstreamQueue] from the mapping.
    public void removeThread(Thread thread) {
        if (thread == null) {
            return;
        }

        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
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

    /// Returns the number of [UpstreamHandles][UpstreamHandle]
    public long getUpstreamCount() {
        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
        if (parent != null) {
            return parent.getUpstreamCount();
        }
        return this.upstreamCount.getOpaque();
    }

    /// Returns the number of threads registered with this LatticeEdge.
    public int getThreadCount() {
        return this.threadCount.intValue();
    }

    /// Returns the number of LatticeEdge horizontally-linked at this edge's layer.
    public int getLayerWidth() {
        if (this.sibling == null) {
            return 1;
        }
        int count = 1;
        LatticeEdge sib = this.sibling;
        while (sib != this) {
            count++;
            sib = sib.sibling;
        }
        return count;
    }

    /// Sets the parent LatticeEdge and transfers all [UpstreamHandles][UpstreamHandle] and
    /// [UpstreamQueues][UpstreamQueue] to it.
    public void setParent(LatticeEdge parent) {
        if (parent == null) {
            PARENT.setRelease(this, null);
            return;
        }

        acquireLock();
        PARENT.setRelease(this, parent);
        LatticeReceiver down = (LatticeReceiver) DOWNSTREAM.getOpaque(this);
        if (down instanceof LatticeEdge rs) {
            rs.setParent(parent);
        }
        transferToParent();
        parent.transferToParent();
        releaseLock();
    }

    /// Sends work downstream.
    @Override
    public void push(AbstractFrame frame) {
        var downstream = (LatticeReceiver) DOWNSTREAM.getOpaque(this);
        if (downstream != null) {
            downstream.push(frame);
        }
    }

    /// Requests work from the [UpstreamHandles][UpstreamHandle]
    @Override
    public void request(long num) {
        if (num < 0) {
            return;
        }
        if ((boolean) CLOSED.getOpaque(this) || this.drain.getOpaque()) {
            return;
        }

        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
        if (parent != null) {
            parent.request(num);
            return;
        }

        UpstreamQueue queue = UpstreamQueue.get(this.aggregators, this.threadCount);
        queue.request(num);
    }

    /// Pulls available work from the [UpstreamHandles][UpstreamHandle] without requesting more
    /// work.
    @Override
    public void pull(Consumer<AbstractFrame> consumer, long demand) {
        if ((boolean) CLOSED.getOpaque(this) || this.drain.getOpaque()) {
            return;
        }

        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
        if (parent != null) {
            parent.pull(consumer, demand);
            return;
        }
        UpstreamQueue queue = UpstreamQueue.get(this.aggregators, this.threadCount);
        queue.pull(consumer, demand);
    }

    /// Transfers this edge's state to its parent.
    protected void transferToParent() {
        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
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

    /// Clears the state and permanently closes.
    @Override
    public void close() {
        if (!CLOSED.compareAndSet(this, false, true)) {
            return;
        }

        var downstream = (LatticeReceiver) DOWNSTREAM.getAcquire(this);
        if (downstream != null) {
            downstream.onComplete();
            DOWNSTREAM.setRelease(this, null);
        }
        this.aggregators.clear();
        this.sibling = null;
        UP_QUEUES.setRelease(this, null);
        this.upstreamHandles.clear();
    }

    /// If the parameter is a LatticeEdge, it sets it as its parent or bubbles it up the chain. If
    /// it is an [UpstreamHandle][UpstreamHandle], it adds it to all
    /// [UpstreamQueues][UpstreamQueue]
    @Override
    public void addUpstream(LatticeInterceptor up) {
        if (up instanceof LatticeEdge dh) {
            setParent(dh);
        } else if (up instanceof UpstreamHandle upstream) {
            acquireLock();

            LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
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

    /// If the parameter is a LatticeEdge, it attempts to recursively send it down the chain. If it
    /// is not, it becomes the floor of the chain.
    @Override
    public void addDownstream(LatticeInterceptor downstream) {
        boolean isEdge = downstream instanceof LatticeEdge;

        var witness = (LatticeReceiver) DOWNSTREAM.compareAndExchange(this, null, downstream);

        if (witness == null) {
            if (isEdge) {
                ((LatticeEdge) downstream).setParent(this);
            } else {
                downstream.addUpstream(this);
            }
            return;
        }

        if (witness instanceof LatticeEdge existingEdge) {
            existingEdge.addDownstream(downstream);
        } else {
            downstream.onError(new IllegalStateException(
                    "Already added as an upstream by a terminal downstream"));
        }
    }

    /// Sets the terminal as the floor of the chain if it hasn't been set. Sends it down the chain
    /// if the downstream is another LatticeEdge.
    public void addDownstream(LatticeReceiver terminal) {
        LatticeReceiver down =
                (LatticeReceiver) DOWNSTREAM.compareAndExchange(this, null, terminal);
        if (down == null) {
            return;
        }
        if (down instanceof LatticeInterceptor rs) {
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
        LatticeReceiver downstream = (LatticeReceiver) DOWNSTREAM.getAcquire(this);
        if (downstream != null) {
            downstream.onError(throwable);
        }
    }
}
