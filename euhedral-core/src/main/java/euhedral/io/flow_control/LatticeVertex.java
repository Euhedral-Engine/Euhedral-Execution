package euhedral.io.flow_control;

import static euhedral.io.utils.MathFunctions.unsignedMultiplyHigh;

import euhedral.io.control_plane.RoutingPolicy;
import euhedral.io.flow_control.UpstreamQueue.UpstreamHandle;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeInterceptor;
import euhedral.io.generics.LatticeSource;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.queues.PartitionedMpmcQueue;
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
/// #### Routing is hash-based and intentionally lightweight
///
/// ```java
/// int idx = (int) unsignedMultiplyHigh(frame.getCombinedHash(), mapSize);
/// this.downstreams[idx].onNext(frame);
/// ```
///
/// **Each frame is routed to exactly one downstream, ensuring stable partitioning under load.**
@SuppressWarnings("unused")
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

    protected final boolean hasCache;

    protected final Logger logger;
    protected final String name;

    protected final PartitionedMpmcQueue<AbstractFrame> cache;
    protected final RoutingPolicy cachePolicy;

    protected final LatticeEdge[] downstreams;
    protected final RoutingFunction routingFunction;

    protected RoutingState routingState = new RoutingState(new int[0]);

    public LatticeVertex(String name, int downstreamCount) {
        this(name, downstreamCount, RoutingFunction.DEFAULT, null, RoutingPolicy.ANYWHERE);
    }

    public LatticeVertex(String name, int downstreamCount, RoutingFunction routingFunction,
            PartitionedMpmcQueue<AbstractFrame> cache, RoutingPolicy cachePolicy) {
        super(new AtomicBoolean(false));
        this.logger = LoggerFactory.getLogger(name);
        this.name = name;
        this.downstreams = new LatticeEdge[downstreamCount];
        this.routingFunction = routingFunction;
        this.sibling = this;

        this.hasCache = cache != null;
        this.cache = cache;
        this.cachePolicy = cachePolicy;
    }

    /// Links the stream as an upstream source.
    public void ingest(LatticeSource stream) {
        UpstreamInterceptor interceptor = new UpstreamInterceptor();
        stream.addDownstream(interceptor);
    }

    public AtomicBoolean getDrainFlag() {
        return this.drain;
    }

    /// Rebuilds the routing table and sets the new downstreams. Must be in drain mode to succeed.
    ///
    /// @return Whether the mapping was changed
    public boolean setDownstreamMapping(BitSet active, LatticeEdge[] handles) {
        if (!this.drain.get()) {
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
        this.drain.set(value);
    }

    public boolean isDrained() {
        return this.cache == null || this.cache.isEmpty();
    }

    /// Adds the interceptor to the upstream. If it is a [LatticeEdge], it bubbles it up and sets
    /// its downstream links' parents to the edge. If it is an [UpstreamHandle][UpstreamHandle], it
    /// defaults to the logic in LatticeEdge.
    @Override
    public void addUpstream(LatticeInterceptor interceptor) {
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
        if(this.downstreams.length < 2) {
            this.downstreams[0].push(frame);
            return;
        }

        if (this.hasCache && !frame.isOrdered()
                && frame.getRoutingPolicy().level <= this.cachePolicy.level) {
            int idx = RoutingFunction.DEFAULT.route(frame, this.cache.partitions());
            if (this.cache.offer(idx, frame)) {
                return;
            }
        }

        RoutingState state = (RoutingState) ROUTING_STATE.getOpaque(this);
        int mapLen = state.mappings.length;

        int logicalIdx = this.routingFunction.route(frame, mapLen);
        int id = state.mappings[logicalIdx];
        this.downstreams[id].push(frame);
    }

    /// Pulls available work from the `parallelQueue` if it is not null. Recursively climbs the
    /// graph and does the same.
    @Override
    public long pull(Consumer<AbstractFrame> consumer, long demand) {
        if (demand <= 0 || consumer == null) {
            return 0;
        }

        long total = 0;
        if (this.cache != null) {
            long tId = Thread.currentThread().getId();
            int startIdx = (int) unsignedMultiplyHigh(tId, this.cache.partitions());
            long count = this.cache.drain(consumer, demand, startIdx);
            demand -= count;
            total += count;
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

    /// Closes and removes all downstreams.
    @Override
    public void close() {
        super.close();
        for (int i = 0; i < this.downstreams.length; i++) {
            if (this.downstreams[i] != null) {
                this.downstreams[i].close();
                this.downstreams[i] = null;
            }
        }
        ROUTING_STATE.setRelease(this, null);
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
        public final boolean isPow2;

        RoutingState(int[] mappings) {
            this.mappings = mappings;
            this.isPow2 = (mappings.length & (mappings.length - 1)) == 0;
            this.mask = mappings.length - 1;
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
        private final PaddedAtomicLong demand = new PaddedAtomicLong(0);
        public LatticeSource upstream;
        private long count = 0;

        @Override
        public void addUpstream(@NonNull LatticeSource upstream) {
            this.upstream = upstream;
            LatticeVertex.this.addUpstream(this);
        }

        @Override
        public void push(AbstractFrame frame) {
            if ((this.count++ & 63) == 0) {
                frame.setIngestNs(System.nanoTime());
            } else {
                frame.setIngestNs(0);
            }

            LatticeVertex.this.push(frame);
        }

        @Override
        public long pull(Consumer<AbstractFrame> consumer, long demand) {
            return LatticeVertex.this.pull(consumer, demand);
        }

        @Override
        public void request(long num) {
            if (num <= 0 || LatticeVertex.this.drain.getOpaque() || this.complete.getOpaque()) {
                return;
            }

            this.demand.accumulateAndGet(num, UpstreamInterceptor::addCap);

            if (this.wip.getAndIncrement() == 0) {
                do {
                    try {
                        long demand = this.demand.getAcquire();
                        if(demand > 0) {
                            this.upstream.request(demand);
                            this.demand.accumulateAndGet(-demand, UpstreamInterceptor::addCap);
                        } else if(this.complete.getOpaque()) {
                            return;
                        }
                    } catch (Throwable t) {
                        logger.error("Upstream threw an exception during request", t);
                        this.complete();
                        break;
                    }
                } while(this.wip.decrementAndGet() != 0);
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
