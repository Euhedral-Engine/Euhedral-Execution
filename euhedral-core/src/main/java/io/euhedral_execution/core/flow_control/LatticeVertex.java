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
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
@SuppressWarnings("unused")
public class LatticeVertex extends LatticeEdge implements AutoCloseable {

    public static final Function<AbstractFrame, Boolean> NO_STOP = frame -> false;

    protected static final VarHandle ROUTING_STATE =
            CommonVarHandles.makeHandle(LatticeVertex.class, "routingState", RoutingState.class);
    private static final VarHandle CLOSED = CommonVarHandles.closed(LatticeVertex.class);

    protected final LatticeEdge[] downstreams;
    protected final RoutingFunction routingFunction;

    private final Logger logger;
    private final ThreadLocal<CacheHead> cacheHead = new ThreadLocal<>();

    protected RoutingState routingState = new RoutingState(new int[0], new int[0]);
    private boolean closed = false;
    private final AtomicLong upstreamSequence = new AtomicLong();

    public LatticeVertex(String name, int downstreamCount) {
        this(name, downstreamCount, RoutingFunction.DEFAULT);
    }

    public LatticeVertex(
            String name,
            int downstreamCount,
            RoutingFunction routingFunction) {
        super(new AtomicBoolean(false));
        this.logger = LoggerFactory.getLogger(Constants.getLoggerName(name));
        this.downstreams = new LatticeEdge[downstreamCount];
        this.routingFunction = routingFunction;
    }

    /// Links the stream as an upstream source.
    public void ingest(LatticeSource stream) {
        UpstreamInterceptor interceptor = new UpstreamInterceptor();
        stream.addDownstream(interceptor);
        interceptor.addUpstream(stream);
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
        int[] activeIndexes = new int[this.downstreams.length];
        Arrays.fill(activeIndexes, -1);
        for (int i = 0; i < this.downstreams.length; i++) {
            if (active.get(i)) {
                activeIndexes[i] = mIdx;
                mappings[mIdx++] = i;
                handles[i].setParent(this);
                this.downstreams[i] = handles[i];
            }
        }

        // Publish both directions together; routing maps only change while this vertex is drained.
        ROUTING_STATE.setVolatile(this, new RoutingState(mappings, activeIndexes));

        for (int i = 0; i < this.downstreams.length; i++) {
            if (!active.get(i) && this.downstreams[i] != null) {
                this.downstreams[i].close();
                this.downstreams[i] = null;
            }
        }
        return true;
    }

    /// Returns the active routing index for a physical downstream ID, or -1 when it is inactive.
    public final int getActiveDownstreamIndex(int downstreamId) {
        RoutingState state = (RoutingState) ROUTING_STATE.getOpaque(this);
        return downstreamId >= 0 && downstreamId < state.activeIndexes.length ? state.activeIndexes[downstreamId] : -1;
    }

    public void setDrain(boolean value) {
        super.drain.setRelease(value);
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
        this.downstreams[idx].push(frame);
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
        }
    }

    /// Defines how the [LatticeVertex] will pick which downstream to send work to.
    @FunctionalInterface
    public interface RoutingFunction {

        RoutingFunction DEFAULT = (frame, mapSize) -> (int) unsignedMultiplyHigh(frame.getRoutingHash(), mapSize);

        /// @param frame   Frame to route
        /// @param mapSize Length of the map array.
        /// @return Zero-based index in the active map, not a physical downstream ID.
        int route(AbstractFrame frame, int mapSize);
    }

    protected static final class RoutingState {

        public final int[] mappings;
        public final int[] activeIndexes;
        public final int mask;

        RoutingState(int[] mappings, int[] activeIndexes) {
            this.mappings = mappings;
            this.activeIndexes = activeIndexes;
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

        @Getter
        private final long sequence = LatticeVertex.this.upstreamSequence.getAndIncrement();

        private final ThreadLocal<ProductivityObservation> productivity = new ThreadLocal<>();

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
            observation().productive = true;
            LatticeVertex.this.push(frame);
        }

        @Override
        public long pull(
                Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand) {
            ProductivityObservation observation = observation();
            if (demand <= 0
                    || this.wip.getOpaque() == 0
                    || LatticeVertex.this.isClosed()
                    || LatticeVertex.this.drain.getOpaque()
                    || isComplete()) {
                observation.restore();
                return 0;
            }

            try {
                observation.stopCondition = stopCondition;
                long pulled = this.upstream.pull(consumer, observation, demand);
                if (pulled > 0) {
                    observation.productive = true;
                    return pulled;
                }
                if (observation.stopped) {
                    observation.restore();
                }
                return pulled;
            } catch (Throwable t) {
                observation.restore();
                logger.error("Upstream threw an exception during a pull", t);
                this.complete();
            } finally {
                observation.stopCondition = null;
            }
            return 0;
        }

        @Override
        public void request(long num) {
            ProductivityObservation observation = observation();
            if (num <= 0
                    || this.wip.getOpaque() == 0
                    || LatticeVertex.this.isClosed()
                    || LatticeVertex.this.drain.getOpaque()
                    || isComplete()) {
                observation.restore();
                return;
            }

            try {
                this.upstream.request(num);
            } catch (Throwable t) {
                observation.restore();
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
            return observation().productive;
        }

        @Override
        public boolean wasPullStopped() {
            return observation().stopped;
        }

        /// Sets only the calling worker's deliberately stale observation.
        @Override
        public void setProductivity(boolean productive) {
            observation().productive = productive;
        }

        @Override
        public boolean acquireLock() {
            boolean acquired = this.wip.getAndIncrement() == 0;
            if (acquired) {
                observation().begin();
            }
            return acquired;
        }

        @Override
        public void releaseLock() {
            this.wip.setRelease(0);
        }

        /// Returns this thread's deliberately stale observation for the shared handle.
        private ProductivityObservation observation() {
            ProductivityObservation observation = this.productivity.get();
            if (observation == null) {
                observation = new ProductivityObservation();
                this.productivity.set(observation);
            }
            return observation;
        }

        /// Plain worker-local state; producers and other workers receive independent observations.
        private static final class ProductivityObservation implements Function<AbstractFrame, Boolean> {

            private Function<AbstractFrame, Boolean> stopCondition;
            private boolean productive = true;
            private boolean previousProductive = true;
            private boolean stopped;

            private void begin() {
                this.previousProductive = this.productive;
                this.productive = false;
                this.stopped = false;
            }

            private void restore() {
                this.productive = this.previousProductive;
            }

            @Override
            public Boolean apply(AbstractFrame frame) {
                if (this.stopCondition == null) {
                    return false;
                }
                boolean stop = this.stopCondition.apply(frame);
                if (stop) {
                    this.stopped = true;
                }
                return stop;
            }
        }
    }
}
