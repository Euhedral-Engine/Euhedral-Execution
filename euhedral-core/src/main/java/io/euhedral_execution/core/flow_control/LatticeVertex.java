package io.euhedral_execution.core.flow_control;

import static io.euhedral_execution.core.utils.MathFunctions.unsignedMultiplyHigh;

import io.euhedral_execution.core.flow_control.UpstreamQueue.UpstreamHandle;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeInterceptor;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.internal.Constants;
import io.euhedral_execution.core.utils.CommonVarHandles;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.core.utils.SpinWait;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;
import io.euhedral_execution.data_structures.queues.BoundedMpmcQueue;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.hashing.HasherApi;
import java.lang.invoke.VarHandle;
import java.util.BitSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Getter;
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

    public static final Function<AbstractFrame, Boolean> NO_STOP = frame -> false;

    protected static final VarHandle ROUTING_STATE =
            CommonVarHandles.makeHandle(LatticeVertex.class, "routingState", RoutingState.class);
    private static final VarHandle CLOSED = CommonVarHandles.closed(LatticeVertex.class);

    protected final LatticeEdge[] downstreams;
    protected final RoutingFunction routingFunction;

    protected final boolean hasCache;
    protected final BoundedMpmcQueue<AbstractFrame>[] remoteCache;
    protected final int cachePool;
    protected final RoutingPolicy cachePolicy;

    private final Logger logger;
    private final PaddedLongAdder cacheCount;
    private final ThreadLocal<CacheHead> cacheHead = new ThreadLocal<>();

    protected RoutingState routingState = new RoutingState(new int[0]);
    private boolean closed = false;

    public LatticeVertex(String name, int downstreamCount) {
        this(name, downstreamCount, RoutingFunction.DEFAULT, 0, RoutingPolicy.ANYWHERE);
    }

    public LatticeVertex(
            String name,
            int downstreamCount,
            RoutingFunction routingFunction,
            int cachePool,
            RoutingPolicy cachePolicy) {
        super(new AtomicBoolean(false));
        this.logger = LoggerFactory.getLogger(Constants.getLoggerName(name));
        this.downstreams = new LatticeEdge[downstreamCount];
        this.routingFunction = routingFunction;

        this.hasCache = cachePool > 0;
        this.cachePool = cachePool;
        this.remoteCache = this.hasCache ? new BoundedMpmcQueue[downstreamCount] : null;
        this.cacheCount = this.hasCache ? new PaddedLongAdder(downstreamCount, false, false) : null;
        this.cachePolicy = cachePolicy;
    }

    /// Links the stream as an upstream source.
    public void ingest(LatticeSource stream) {
        UpstreamInterceptor interceptor = new UpstreamInterceptor();
        stream.addDownstream(interceptor);
        interceptor.addUpstream(stream);
    }

    @Override
    public void register() {
        if (this.hasCache) {
            CacheHead head = this.cacheHead.get();
            if (head == null) {
                int core = SystemInfo.getCpuInfo(ThreadTools.getCpu()).core();
                this.cacheHead.set(new CacheHead(this.cacheCount.fromRawIdx(core)));
            }
        }
        super.register();
    }

    @Override
    public long getUpstreamCacheCapacity() {
        long total = 0;
        if (this.hasCache) {
            total += this.cachePool;
        }
        total += super.getUpstreamCacheCapacity();
        return total < 0 ? Long.MAX_VALUE : total;
    }

    @Override
    public long getUpstreamCacheCount() {
        long total = 0;

        if (this.hasCache) {
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

        int mIdx = 0;
        int[] mappings = new int[active.cardinality()];
        for (int i = 0; i < this.downstreams.length; i++) {
            if (active.get(i)) {
                mappings[mIdx++] = i;
                handles[i].setParent(this);
                this.downstreams[i] = handles[i];

                if (this.hasCache) {
                    this.remoteCache[i] = new BoundedMpmcQueue<>(Math.max(4, this.cachePool / active.cardinality()));
                }
            } else if (this.hasCache && this.remoteCache[i] != null) {
                this.remoteCache[i].clear();
                this.remoteCache[i] = null;
            }
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
        if (!this.hasCache) {
            return true;
        }
        for (var queue : this.remoteCache) {
            if (queue != null && !queue.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Forcefully clears this vertex's remote routing caches. The caller must first stop ingress or
     * place the owning graph in drain mode so producers cannot race the reset.
     *
     * @return the estimated number of cached frames removed
     */
    public long clearCachedFrames() {
        if (!this.hasCache) {
            return 0;
        }

        long cleared = Math.max(0, this.cacheCount.sumAndReset());
        for (var queue : this.remoteCache) {
            if (queue != null) {
                queue.clear();
            }
        }
        this.cacheHead.remove();
        return cleared;
    }

    /// Adds the interceptor to the upstream. If it is a [LatticeEdge], it bubbles it up and sets
    /// its downstream links' parents to the edge. If it is an [UpstreamHandle][UpstreamHandle], it
    /// defaults to the logic in LatticeEdge.
    @Override
    public void addUpstream(LatticeInterceptor interceptor) {
        if ((boolean) CLOSED.getOpaque(this)) {
            throw new RuntimeException("Cannot add upstream after closing.");
        }

        if (interceptor instanceof LatticeEdge edge) {
            setParent(edge);
            edge.addDownstream(this);
            for (var down : this.downstreams) {
                if (down != null) {
                    LatticeEdge parent = (LatticeEdge) PARENT.getOpaque(this);
                    down.setParent(parent);
                }
            }
        } else if (interceptor instanceof UpstreamHandle upstream) {
            this.logger.trace("Adding upstream handle...");
            SpinWait.awaitWhile(super.drain::getOpaque);

            for (int i = 0; i < UPSTREAMS.length; i++) {
                MpscQueue<UpstreamHandle> queue = UPSTREAMS[i];
                if (queue != null && ACTIVE_PARTITIONS.getAcquire(i) > 0) {
                    queue.offer(upstream);
                }
            }

            UPSTREAM_COUNT.incrementAndGet();
            this.logger.trace("Added upstream handle.");
        }
    }

    /// Picks a downstream link and sends work down.
    @Override
    public void push(AbstractFrame frame) {
        if ((boolean) CLOSED.getOpaque(this)) {
            return;
        }
        if (this.downstreams.length < 2) {
            this.downstreams[0].push(frame);
            return;
        }

        RoutingState state = (RoutingState) ROUTING_STATE.getOpaque(this);
        int mapLen = state.mappings.length;

        int logicalIdx = this.routingFunction.route(frame, mapLen);
        int idx = state.mappings[logicalIdx];

        if (this.hasCache && !frame.isOrdered() && frame.getRoutingPolicy().level <= this.cachePolicy.level) {
            CacheHead head = this.cacheHead.get();
            if (this.remoteCache[idx].offer(frame)) {
                FlowThread.FlowContext context = FlowThread.getContext();
                if (context != null) {
                    context.satisfiedRequest++;
                }
                if (head != null) {
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
    public long pull(Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
        if (demand <= 0 || consumer == null || (boolean) CLOSED.getOpaque(this) || super.drain.getOpaque()) {
            return 0;
        }

        long total = 0;
        if (this.remoteCache != null) {
            CacheHead head = this.cacheHead.get();
            if (head != null) {
                RoutingState state = (RoutingState) ROUTING_STATE.getOpaque(this);
                long bucket = Math.max(1, demand / state.mappings.length);

                int cycles = 0;
                while (cycles < state.mappings.length && demand > 0) {
                    int idx = state.mappings[head.idx];
                    long count = this.remoteCache[idx].drain(consumer, stopCondition, Math.min(bucket, demand));
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
            total += parent.pull(consumer, stopCondition, demand);
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
    public boolean isClosed() {
        return (boolean) CLOSED.getOpaque(this);
    }

    @Override
    public void close() {
        if (!CLOSED.compareAndSet(this, false, true)) {
            return;
        }
        super.close();
        for (int i = 0; i < this.downstreams.length; i++) {
            if (this.downstreams[i] != null) {
                this.downstreams[i].close();
            }
            if (this.hasCache && this.remoteCache[i] != null) {
                this.remoteCache[i].clear();
            }
        }
    }

    /// Defines how the [LatticeVertex] will pick which downstream to send work to.
    @FunctionalInterface
    public interface RoutingFunction {

        RoutingFunction DEFAULT = (frame, mapSize) -> (int) unsignedMultiplyHigh(frame.getRoutingHash(), mapSize);

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

        final int counterIdx;
        int idx = 0;

        CacheHead(int counterIdx) {
            this.counterIdx = counterIdx;
        }
    }

    /// Wraps the [LatticeSource][io.euhedral_execution.core.generics.LatticeSource] in an object that contains the
    /// state of the stream. Requests and
    /// pulls are guaranteed to be made by 1 thread at a time.
    public class UpstreamInterceptor extends UpstreamHandle {

        private static final VarHandle COMPLETE = CommonVarHandles.complete(UpstreamInterceptor.class);

        @Getter
        private final long id = HasherApi.mix(ThreadLocalRandom.current().nextLong());

        private final ThreadLocal<Boolean> productive = new ThreadLocal<>();

        private final PaddedAtomicLong wip = new PaddedAtomicLong(0);
        public LatticeSource upstream;
        public boolean complete = false;

        private static long addCap(long num1, long num2) {
            long sum = num1 + num2;
            return sum < 0 ? Long.MAX_VALUE : sum;
        }

        @Override
        public void addUpstream(@NonNull LatticeSource upstream) {
            this.upstream = upstream;
            LatticeVertex.this.addUpstream(this);
        }

        @Override
        public void push(AbstractFrame frame) {
            this.productive.set(Boolean.TRUE);
            LatticeVertex.this.push(frame);
        }

        @Override
        public long pull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            if (demand <= 0
                    || this.wip.getOpaque() == 0
                    || LatticeVertex.this.isClosed()
                    || LatticeVertex.this.drain.getOpaque()
                    || isComplete()) {
                return 0;
            }

            try {
                long pulled = this.upstream.pull(consumer, stopCondition, demand);
                if (pulled > 0) {
                    this.productive.set(Boolean.TRUE);
                }
                return pulled;
            } catch (Throwable t) {
                logger.error("Upstream threw an exception during a pull", t);
                this.complete();
            }
            return 0;
        }

        @Override
        public void request(long num) {
            if (num <= 0
                    || this.wip.getOpaque() == 0
                    || LatticeVertex.this.isClosed()
                    || LatticeVertex.this.drain.getOpaque()
                    || isComplete()) {
                return;
            }

            try {
                this.upstream.request(num);
            } catch (Throwable t) {
                logger.error("Upstream threw an exception during request", t);
                this.complete();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            if (COMPLETE.compareAndSet(this, false, true)) {
                logger.error("UpstreamHandle Error", throwable);
                removeUpstream();
            }
        }

        @Override
        public void onComplete() {
            if (COMPLETE.compareAndSet(this, false, true)) {
                logger.trace("UpstreamHandle Complete");
                removeUpstream();
            }
        }

        @Override
        public void complete() {
            if (COMPLETE.compareAndSet(this, false, true)) {
                logger.trace("Closing UpstreamHandle");
                this.upstream.complete();
                removeUpstream();
            }
        }

        @Override
        public boolean isComplete() {
            return (boolean) COMPLETE.getOpaque(this);
        }

        @Override
        public boolean isProductive() {
            Boolean prod = this.productive.get();
            if (prod == null) {
                this.productive.set(Boolean.TRUE);
                return true;
            }
            return prod;
        }

        @Override
        public boolean acquireLock() {
            boolean acquired = this.wip.getAndIncrement() == 0;
            if (acquired) {
                this.productive.set(Boolean.FALSE);
            }
            return acquired;
        }

        @Override
        public void releaseLock() {
            this.wip.setRelease(0);
        }
    }
}
