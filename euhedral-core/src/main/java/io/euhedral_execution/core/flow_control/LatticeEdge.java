package io.euhedral_execution.core.flow_control;

import io.euhedral_execution.core.flow_control.UpstreamQueue.UpstreamHandle;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeInterceptor;
import io.euhedral_execution.core.generics.LatticeReceiver;
import io.euhedral_execution.core.internal.Constants;
import io.euhedral_execution.core.utils.CommonVarHandles;
import io.euhedral_execution.core.utils.SpinWait;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CoreInfo;
import io.euhedral_execution.hashing.HasherApi;
import java.lang.invoke.VarHandle;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The main infrastructure class of Euhedral Core
///
/// This is the structural backbone of the entire system. A `LatticeEdge` forms a dynamic execution
/// graph by recursively linking to other edges whenever it is attached upstream or downstream.
///
/// When another `LatticeEdge` is connected, it becomes part of the same chain, effectively
/// extending the execution topology. When an `UpstreamHandle` is added, it is propagated upward
/// through the graph. When a `LatticeReceiver` is attached, it becomes the execution boundary (the
/// "floor") of that branch.
///
/// Work flows downward through the graph toward receivers, while demand and backpressure flow
/// upward toward upstream sources. The structure is designed to continuously reconcile both
/// directions under concurrent mutation.
@SuppressWarnings({"unchecked", "unused"})
public class LatticeEdge extends UpstreamHandle {

    protected static final VarHandle DOWNSTREAM = CommonVarHandles.downstream(LatticeEdge.class);
    protected static final VarHandle PARENT = CommonVarHandles.makeHandle(LatticeEdge.class,
            "parent", LatticeEdge.class);

    protected static final MpscQueue<UpstreamHandle>[] UPSTREAMS;
    protected static final AtomicLongArray ACTIVE_PARTITIONS;
    protected static final AtomicLong THREAD_COUNT = new AtomicLong(0);

    protected static final PaddedAtomicLong UPSTREAM_COUNT = new PaddedAtomicLong(0);

    private static final VarHandle CLOSED = CommonVarHandles.closed(LatticeEdge.class);
    private static final Logger LOGGER = LoggerFactory.getLogger(
            Constants.getLoggerName(LatticeEdge.class));


    static {
        try {
            UPSTREAMS = new MpscQueue[SystemInfo.getMaxCoreId() + 1];
            ACTIVE_PARTITIONS = new AtomicLongArray(UPSTREAMS.length);
            for (int i = 0; i < UPSTREAMS.length; i++) {
                CoreInfo info = SystemInfo.getCoreInfo(i);
                if (info != null) {
                    UPSTREAMS[i] = new MpscQueue<>(256);
                }
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Getter
    protected final long id = HasherApi.mix(ThreadLocalRandom.current().nextLong());

    protected final AtomicBoolean drain;

    protected LatticeReceiver downstream = null;
    protected LatticeEdge parent = null;

    private boolean closed = false;

    public LatticeEdge(AtomicBoolean drain) {
        this.drain = drain;
    }

    /// Creates a thread-local [UpstreamQueue] object for the calling thread. This queue contains
    /// all [UpstreamHandles][UpstreamHandle] associated with the LatticeEdge.
    ///
    /// This does not need to be called to avoid errors. It is a micro-optimization.
    public void register() {
        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
        if (parent != null) {
            parent.register();
        } else {
            UpstreamQueue queue = getThreadUpstreamQueue();
            int core = queue.core;
            ACTIVE_PARTITIONS.setRelease(core, 1);
            LOGGER.trace("Registered thread on core {}", core);
        }
    }

    /// Gets the thread-local [UpstreamQueue] object for the calling thread. This queue contains all
    /// [UpstreamHandles][UpstreamHandle] associated with the LatticeEdge.
    public UpstreamQueue getThreadUpstreamQueue() {
        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
        if (parent != null) {
            return parent.getThreadUpstreamQueue();
        }

        UpstreamQueue queue = UpstreamQueue.get(UPSTREAMS, UPSTREAM_COUNT, THREAD_COUNT);
        queue.getTrueUpstreamCount();

        return queue;
    }

    /// Removes a thread and its [UpstreamQueue] from the mapping.
    public void removeThread() {

        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
        if (parent != null) {
            parent.removeThread();
            return;
        }

        UpstreamQueue queue = UpstreamQueue.UP_QUEUE.get();
        if (queue != null) {
            THREAD_COUNT.decrementAndGet();
            UpstreamQueue.UP_QUEUE.remove();
            int core = queue.core;
            if (core >= 0 && core < UPSTREAMS.length) {
                ACTIVE_PARTITIONS.setRelease(core, 0);
                MpscQueue<UpstreamHandle> upstreams = UPSTREAMS[core];
                if (upstreams != null) {
                    upstreams.clear();
                }
            }
            LOGGER.trace("Removed entry for thread on core {}", core);
        }
    }

    public long getUpstreamCacheCapacity() {
        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
        if (parent != null) {
            return parent.getUpstreamCacheCapacity();
        }
        return 0;
    }

    public long getUpstreamCacheCount() {
        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
        if (parent != null) {
            return parent.getUpstreamCacheCount();
        }
        return 0;
    }

    /// Returns the number of [UpstreamHandles][UpstreamHandle]
    public long getUpstreamHandleCount() {
        return UPSTREAM_COUNT.getOpaque();
    }

    /// Returns the number of threads registered with this LatticeEdge.
    public int getThreadCount() {
        return THREAD_COUNT.intValue();
    }

    /// Sets the parent LatticeEdge.
    public void setParent(LatticeEdge parent) {
        PARENT.setRelease(this, parent);
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
        if (num <= 0) {
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

        UpstreamQueue queue = UpstreamQueue.get(UPSTREAMS, UPSTREAM_COUNT, THREAD_COUNT);
        queue.request(num);
    }

    /// Pulls available work from the [UpstreamHandles][UpstreamHandle] without requesting more
    /// work.
    @Override
    public long pull(Consumer<AbstractFrame> consumer,
            Function<AbstractFrame, Boolean> stopCondition, long demand) {
        if (demand <= 0 || (boolean) CLOSED.getOpaque(this) || this.drain.getOpaque()) {
            return 0;
        }

        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
        if (parent != null) {
            return parent.pull(consumer, stopCondition, demand);
        }
        UpstreamQueue queue = UpstreamQueue.get(UPSTREAMS, UPSTREAM_COUNT, THREAD_COUNT);
        return queue.pull(consumer, stopCondition, demand);
    }

    @Override
    public boolean isComplete() {
        return isClosed();
    }

    /// Clears the state and permanently closes.
    public void close() {
        if (!CLOSED.compareAndSet(this, false, true)) {
            return;
        }

        var downstream = (LatticeReceiver) DOWNSTREAM.getAcquire(this);
        if (downstream != null) {
            downstream.onComplete();
            DOWNSTREAM.setRelease(this, null);
        }
    }

    public boolean isClosed() {
        return (boolean) CLOSED.getOpaque(this);
    }

    protected void removeUpstream() {
        UPSTREAM_COUNT.decrementAndGet();
    }

    /// If the parameter is a LatticeEdge, it sets it as its parent or bubbles it up the chain. If
    /// it is an [UpstreamHandle][UpstreamHandle], it adds it to all
    /// [UpstreamQueues][UpstreamQueue]
    @Override
    public void addUpstream(LatticeInterceptor up) {
        if (up instanceof LatticeEdge dh) {
            setParent(dh);
        } else if (up instanceof UpstreamHandle upstream) {
            LOGGER.trace("Adding upstream handle...");
            SpinWait.awaitWhile(this.drain::getOpaque);

            for (int i = 0; i < UPSTREAMS.length; i++) {
                MpscQueue<UpstreamHandle> queue = UPSTREAMS[i];
                if (queue != null && ACTIVE_PARTITIONS.getAcquire(i) > 0) {
                    queue.offer(upstream);
                }
            }
            UPSTREAM_COUNT.incrementAndGet();
            LOGGER.trace("Added upstream handle.");
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

    @Override
    public void onError(Throwable throwable) {
        LatticeReceiver downstream = (LatticeReceiver) DOWNSTREAM.getAcquire(this);
        if (downstream != null) {
            downstream.onError(throwable);
        }
    }
}
