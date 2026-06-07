package euhedral.io.flow_control;

import static euhedral.io.utils.MathFunctions.unsignedMultiplyHigh;

import euhedral.io.flow_control.UpstreamQueue.UpstreamHandle;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeInterceptor;
import euhedral.io.generics.LatticeSource;
import euhedral.queues.PartitionedMpscQueue;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
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

    protected final boolean terminal;

    protected final Logger logger;
    protected final String name;

    /// This queue acts like a capacitor for contention. When the number of upstreams is very low,
    /// downstream demand hits the same upstreams repeatedly. This small queue gives them another
    /// squirrel to chase.
    protected final PartitionedMpscQueue<AbstractFrame> parallelQueue;

    protected final LatticeEdge[] downstreams;
    protected final RoutingFunction routingFunction;
    private final PaddedAtomicLong wip;

    protected RoutingState routingState = new RoutingState(new int[0]);

    public LatticeVertex(String name, int downstreamCount) {
        this(name, downstreamCount, RoutingFunction.DEFAULT, false);
    }

    public LatticeVertex(String name, int downstreamCount, RoutingFunction routingFunction,
            boolean terminal) {
        super(new AtomicBoolean(false));
        this.terminal = terminal;
        this.logger = LoggerFactory.getLogger(name);
        this.name = name;
        this.downstreams = new LatticeEdge[downstreamCount];
        this.routingFunction = routingFunction;
        this.sibling = this;
        if (!terminal) {
            this.wip = new PaddedAtomicLong(0);
            this.parallelQueue = new PartitionedMpscQueue<>(2_048);
        } else {
            this.wip = null;
            this.parallelQueue = null;
        }
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
        return this.parallelQueue == null || this.parallelQueue.isEmpty();
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
        RoutingState state = (RoutingState) ROUTING_STATE.getOpaque(this);
        int mapLen = state.mappings.length;

        int logicalIdx = this.routingFunction.route(frame, mapLen);
        int id = state.mappings[logicalIdx];
        this.downstreams[id].push(frame);
    }

    /// Pulls available work from the `parallelQueue` if it is not null. Recursively climbs the
    /// graph and does the same.
    @Override
    public void pull(Consumer<AbstractFrame> consumer, long demand) {
        if (demand <= 0 || consumer == null) {
            return;
        }

        if (this.parallelQueue != null && !this.parallelQueue.isEmpty()) {
            if (this.wip.compareAndSet(0, 1)) {
                try {
                    long count = this.parallelQueue.drain(consumer, demand);
                    demand -= count;
                } finally {
                    this.wip.set(0);
                }
            }
        }

        LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
        if (parent != null) {
            parent.pull(consumer, demand);
        }
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
            if (num1 < 0 || num2 < 0) {
                return Long.MAX_VALUE;
            }
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

            if (frame.isOrdered()) {
                LatticeVertex.this.push(frame);
                return;
            }

            boolean hasQueue = LatticeVertex.this.parallelQueue != null;
            if (hasQueue && LatticeVertex.this.parallelQueue.offer(frame)) {
                return;
            }

            LatticeVertex.this.push(frame);
        }

        @Override
        public void pull(Consumer<AbstractFrame> consumer, long demand) {
            LatticeVertex.this.pull(consumer, demand);
        }

        @Override
        public void request(long num) {
            if (num <= 0 || LatticeVertex.this.drain.getOpaque() || this.complete.getOpaque()) {
                return;
            }

            if (this.wip.compareAndSet(0, 1)) {
                try {
                    long demand = addAndReset(num);
                    this.upstream.request(demand);
                } catch (Throwable t) {
                    logger.error("Upstream threw an exception during request", t);
                    this.complete();
                } finally {
                    this.wip.set(0);
                }
                return;
            }
            this.demand.accumulateAndGet(num, UpstreamInterceptor::addCap);
            if (this.wip.compareAndSet(0, 1)) {
                try {
                    long d = addAndReset(0);
                    this.upstream.request(d);
                } catch (Throwable t) {
                    logger.error("UpstreamHandle threw an exception during request", t);
                    this.complete();
                } finally {
                    this.wip.set(0);
                }
            }
        }

        private long addAndReset(long num) {
            long sum = this.demand.getAndSet(0);
            sum += num;
            if (sum < 0) {
                return Long.MAX_VALUE;
            }
            return sum;
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
            }
        }

        @Override
        public void complete() {
            if (this.complete.compareAndSet(false, true)) {
                logger.trace("Closing UpstreamHandle");
                this.upstream.complete();
            }
        }

        @Override
        public boolean isComplete() {
            return this.complete.getOpaque();
        }
    }
}
