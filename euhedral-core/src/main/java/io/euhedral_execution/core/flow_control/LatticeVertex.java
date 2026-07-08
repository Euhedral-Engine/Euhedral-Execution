package io.euhedral_execution.core.flow_control;

import static io.euhedral_execution.core.utils.MathFunctions.unsignedMultiplyHigh;

import io.euhedral_execution.core.flow_control.UpstreamQueue.UpstreamHandle;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeInterceptor;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import io.euhedral_execution.data_structures.queues.BoundedMpmcQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The main routing logic of Euhedral Core
///
/// This class behaves similarly to [LatticeEdge], but extends it with explicit fan-out routing
/// across multiple downstream branches. It is responsible for distributing work deterministically
/// across a fixed topology.
///
/// #### Routing is hash-based
///
/// ```java
/// int idx = (int) unsignedMultiplyHigh(frame.getCombinedHash(), mapSize);
/// this.downstreams[idx].onNext(frame);
/// ```
///
/// **Each frame is routed to exactly one downstream, ensuring stable partitioning under load.**
@SuppressWarnings({"unchecked", "unused"})
public class LatticeVertex extends LatticeEdge implements AutoCloseable {

    protected static final VarHandle ROUTING_STATE;

    static {
        try {
            ROUTING_STATE = MethodHandles.lookup()
                    .findVarHandle(LatticeVertex.class, "routingState", RoutingState.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected final AtomicBoolean closed = new AtomicBoolean(false);
    protected final boolean hasCache;

    protected final Logger logger;

    protected final LatticeEdge[] downstreams;
    protected final RoutingFunction routingFunction;

    protected final BoundedMpmcQueue<AbstractFrame>[] cache;
    protected final int cachePool;
    protected final RoutingPolicy cachePolicy;
    private final PaddedLongAdder cacheCount;
    private final ThreadLocal<CacheHead> cacheHead = new ThreadLocal<>();

    protected RoutingState routingState = new RoutingState(new int[0]);

    public LatticeVertex(String name, int downstreamCount) {
        this(name, downstreamCount, RoutingFunction.DEFAULT, 0, RoutingPolicy.ANYWHERE);
    }

    public LatticeVertex(String name, int downstreamCount, RoutingFunction routingFunction,
            int cachePool, RoutingPolicy cachePolicy) {
        super(new AtomicBoolean(false));
        this.logger = LoggerFactory.getLogger(name);
        this.downstreams = new LatticeEdge[downstreamCount];
        this.routingFunction = routingFunction;
        this.sibling = this;

        this.hasCache = cachePool > 0;
        this.cachePool = cachePool;
        this.cache = this.hasCache ? new BoundedMpmcQueue[downstreamCount] : null;
        this.cacheCount = this.hasCache ? new PaddedLongAdder(downstreamCount, false, false) : null;
        this.cachePolicy = cachePolicy;
    }

    /// Links the stream as an upstream source.
    public void ingest(LatticeSource stream) {
        UpstreamInterceptor interceptor = new UpstreamInterceptor();
        stream.addDownstream(interceptor);
    }

    @Override
    public void register(int id) {
        if(this.hasCache) {
            CacheHead head = this.cacheHead.get();
            if(head == null) {
                this.cacheHead.set(new CacheHead(id, this.cacheCount.fromRawIdx(id)));
            }
        }
        super.register(id);
    }

    @Override
    public long getUpstreamCacheCapacity() {
        long total = 0;
        if(this.hasCache) {
            total += this.cachePool;
        }
        total += super.getUpstreamCacheCount();
        return total < 0 ? Long.MAX_VALUE : total;
    }

    @Override
    public long getUpstreamCacheCount() {
        long total = 0;

        if(this.hasCache) {
            total = this.cacheCount.sum();
        }
        total += super.getUpstreamCacheCount();
        return total < 0 ? Long.MAX_VALUE : total;
    }

    public AtomicBoolean getDrainFlag() {
        return super.drain;
    }

    /// Rebuilds the routing table and sets the new downstreams. Must be in drain mode to succeed.
    ///
    /// @return Whether the mapping was changed
    public boolean setDownstreamMapping(BitSet active, LatticeEdge[] handles) {
        if (!super.drain.get()) {
            return false;
        }

        LatticeEdge first = null;
        LatticeEdge prev = null;
        LatticeEdge curr = null;
        int mIdx = 0;
        int[] mappings = new int[active.cardinality()];
        for (int i = 0; i < this.downstreams.length; i++) {
            if (active.get(i)) {
                mappings[mIdx++] = i;
                handles[i].setParent(this);
                this.downstreams[i] = handles[i];

                if(this.hasCache) {
                    this.cache[i] = new BoundedMpmcQueue<>(Math.max(4, this.cachePool / active.cardinality()));
                }

                if (curr == null) {
                    first = handles[i];
                    curr = handles[i];
                } else if (prev == null) {
                    first.sibling = curr;
                    prev = curr;
                    curr = handles[i];
                    prev.sibling = curr;
                } else {
                    curr.sibling = handles[i];
                    prev = curr;
                    curr = handles[i];
                }
            } else if(this.hasCache && this.cache[i] != null) {
                this.cache[i].clear();
                this.cache[i] = null;
            }
        }
        if (curr != null) {
            curr.sibling = first;
        }

        ROUTING_STATE.setVolatile(this, new RoutingState(mappings));

        for (int i = 0; i < this.downstreams.length; i++) {
            if (!active.get(i) && this.downstreams[i] != null) {
                this.downstreams[i].close();
                this.downstreams[i] = null;
            }
        }
        return true;
    }

    public void setDrain(boolean value) {
        super.drain.setRelease(value);
    }

    public boolean isDrained() {
        if(!this.hasCache) {
            return true;
        }
        for(var queue : this.cache) {
            if(queue != null && !queue.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /// Adds the interceptor to the upstream. If it is a [LatticeEdge], it bubbles it up and sets
    /// its downstream links' parents to the edge. If it is an [UpstreamHandle][UpstreamHandle], it
    /// defaults to the logic in LatticeEdge.
    @Override
    public void addUpstream(LatticeInterceptor interceptor) {
        if(this.closed.getAcquire()) {
            throw new RuntimeException("Cannot add upstream after closed.");
        }

        if (interceptor instanceof LatticeEdge edge) {
            super.addUpstream(edge);
            edge.addDownstream(this);
            for (var down : this.downstreams) {
                if (down != null) {
                    LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
                    down.setParent(parent);
                }
            }
        } else if (interceptor instanceof UpstreamHandle) {
            super.addUpstream(interceptor);
        }
    }

    /// Picks a downstream link and sends work down.
    @Override
    public void push(AbstractFrame frame) {
        if(this.closed.getOpaque()) {
            return;
        }
        if(this.downstreams.length < 2) {
            this.downstreams[0].push(frame);
            return;
        }

        RoutingState state = (RoutingState) ROUTING_STATE.getOpaque(this);
        int mapLen = state.mappings.length;

        int logicalIdx = this.routingFunction.route(frame, mapLen);
        int idx = state.mappings[logicalIdx];

        if (this.hasCache && !frame.isOrdered()
                && frame.getRoutingPolicy().level <= this.cachePolicy.level) {
            CacheHead head = this.cacheHead.get();
            if (this.cache[idx].offer(frame)) {
                FlowThread.FlowContext context = FlowThread.getContext();
                if(context != null) {
                    context.satisfiedRequest++;
                }
                if(head != null) {
                    this.cacheCount.increment(head.counterIdx);
                } else {
                    this.cacheCount.increment(this.cacheCount.fromRawIdx(frame.getRoutingHash()));
                }
                return;
            }
        }

        this.downstreams[idx].push(frame);
    }

    /// Pulls available work from the `parallelQueue` if it is not null. Recursively climbs the
    /// graph and does the same.
    @Override
    public long pull(Consumer<AbstractFrame> consumer, long demand) {
        if (demand <= 0 || consumer == null || this.closed.getOpaque() || super.drain.getOpaque()) {
            return 0;
        }

        long total = 0;
        if (this.cache != null) {
            CacheHead head = this.cacheHead.get();
            if(head != null) {
                RoutingState state = (RoutingState) ROUTING_STATE.getOpaque(this);
                long bucket = Math.max(1, demand / state.mappings.length);

                int cycles = 0;
                while(cycles < state.mappings.length && demand > 0) {
                    int idx = state.mappings[head.idx];
                    long count = this.cache[idx].drain(consumer, Math.min(bucket, demand));
                    total += count;
                    demand -= count;
                    cycles++;

                    if (count > 0) {
                        this.cacheCount.add(head.counterIdx, -count);
                    }
                    head.idx = (head.idx + 1) % state.mappings.length;
                }
            }
        }

        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
        if (parent != null) {
            total += parent.pull(consumer, demand);
        }
        return total;
    }

    @Override
    public void onError(Throwable throwable) {
        for (var down : this.downstreams) {
            if (down != null) {
                down.onError(throwable);
            }
        }
    }

    @Override
    public void close() {
        if(!this.closed.compareAndSet(false, true)) {
            return;
        }
        super.close();
        for (int i = 0; i < this.downstreams.length; i++) {
            if (this.downstreams[i] != null) {
                this.downstreams[i].close();
            }
            if(this.hasCache && this.cache[i] != null) {
                this.cache[i].clear();
            }
        }
    }

    /// Defines how the [LatticeVertex] will pick which downstream to send work to.
    @FunctionalInterface
    public interface RoutingFunction {

        RoutingFunction DEFAULT =
                (frame, mapSize) -> (int) unsignedMultiplyHigh(frame.getRoutingHash(), mapSize);

        /// @param frame   Frame to route
        /// @param mapSize Length of the map array.
        int route(AbstractFrame frame, int mapSize);
    }

    protected static final class RoutingState {

        public final int[] mappings;
        public final int mask;

        RoutingState(int[] mappings) {
            this.mappings = mappings;
            this.mask = mappings.length - 1;
        }
    }

    private static final class CacheHead {
        final int id;
        final int counterIdx;
        int idx = 0;

        CacheHead(int id, int counterIdx) {
            this.id = id;
            this.counterIdx = counterIdx;
        }
    }

    /// Wraps the [LatticeSource] in an object that contains the state of the stream. Requests and
    /// pulls are guaranteed to be made by 1 thread at a time.
    public class UpstreamInterceptor extends UpstreamHandle {

        private static long addCap(long num1, long num2) {
            long sum = num1 + num2;
            return sum < 0 ? Long.MAX_VALUE : sum;
        }

        public final AtomicBoolean complete = new AtomicBoolean(false);
        private final PaddedAtomicLong wip = new PaddedAtomicLong(0);
        public LatticeSource upstream;

        @Override
        public void addUpstream(@NonNull LatticeSource upstream) {
            this.upstream = upstream;
            LatticeVertex.this.addUpstream(this);
        }

        @Override
        public void push(AbstractFrame frame) {
            LatticeVertex.this.push(frame);
        }

        @Override
        public long pull(Consumer<AbstractFrame> consumer, long demand) {
            return LatticeVertex.this.pull(consumer, demand);
        }

        @Override
        public void request(long num) {
            if (num <= 0 || LatticeVertex.this.closed.getOpaque() || LatticeVertex.this.drain.getOpaque() || this.complete.getOpaque()) {
                return;
            }

            if (this.wip.getAndIncrement() == 0) {
                try {
                    this.upstream.request(num);
                } catch (Throwable t) {
                    logger.error("Upstream threw an exception during request", t);
                    this.complete();
                } finally {
                    this.wip.lazySet(0);
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (this.complete.compareAndSet(false, true)) {
                logger.error("UpstreamHandle Error", throwable);
            }
        }

        @Override
        public void onComplete() {
            if (this.complete.compareAndSet(false, true)) {
                logger.trace("UpstreamHandle Complete");
                removeUpstream(this);
            }
        }

        @Override
        public void complete() {
            if (this.complete.compareAndSet(false, true)) {
                logger.trace("Closing UpstreamHandle");
                this.upstream.complete();
                removeUpstream(this);
            }
        }

        @Override
        public boolean isComplete() {
            return this.complete.getOpaque();
        }
    }
}
