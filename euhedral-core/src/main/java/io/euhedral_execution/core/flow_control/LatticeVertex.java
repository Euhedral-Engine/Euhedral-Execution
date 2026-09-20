package io.euhedral_execution.core.flow_control;

import static io.euhedral_execution.core.utils.MathFunctions.unsignedMultiplyHigh;

import io.euhedral_execution.core.flow_control.UpstreamQueue.UpstreamHandle;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeInterceptor;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.internal.Constants;
import io.euhedral_execution.core.utils.CommonVarHandles;
import io.euhedral_execution.core.utils.SpinWait;
import io.euhedral_execution.data_structures.atomics.PaddedAtomicLong;
import io.euhedral_execution.data_structures.queues.MpscQueue;
import io.euhedral_execution.hashing.HasherApi;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    protected final SnapshotRoutingFunction snapshotRoutingFunction;

    private final Logger logger;
    private final ThreadLocal<CacheHead> cacheHead = new ThreadLocal<>();
    private final Object routingUpdateLock = new Object();
    private final Map<LatticeEdge, Integer> handleReferences = new IdentityHashMap<>();
    private final Map<LatticeEdge, List<Runnable>> handleRetirements = new IdentityHashMap<>();
    private final List<Runnable> closeRetirements = new ArrayList<>();
    private boolean routingClosed;

    protected RoutingState routingState = new RoutingState(new int[0], new int[0], new LatticeEdge[0]);
    private boolean closed = false;
    private final AtomicLong upstreamSequence = new AtomicLong();

    public LatticeVertex(String name, int downstreamCount) {
        this(name, downstreamCount, RoutingFunction.DEFAULT);
    }

    public LatticeVertex(String name, int downstreamCount, RoutingFunction routingFunction) {
        this(name, downstreamCount, (frame, mapSize, state) -> routingFunction.route(frame, mapSize));
    }

    public LatticeVertex(String name, int downstreamCount, SnapshotRoutingFunction routingFunction) {
        super(new AtomicBoolean(false));
        this.logger = LoggerFactory.getLogger(Constants.getLoggerName(name));
        this.downstreams = new LatticeEdge[downstreamCount];
        this.snapshotRoutingFunction = Objects.requireNonNull(routingFunction);
        this.routingFunction = (frame, mapSize) -> routingFunction.route(frame, mapSize, null);
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
    /// A published state owns its downstream handles until every route admitted against that state
    /// has finished. Remapping therefore replaces the immutable state first and retires old handles
    /// only after their readers release them.
    ///
    /// @return Whether the mapping was changed
    public boolean setDownstreamMapping(BitSet active, LatticeEdge[] handles) {
        if (!super.drain.get() || isClosed() || handles == null) {
            return false;
        }

        RoutingState retired;
        LatticeEdge[] detached;
        synchronized (this.routingUpdateLock) {
            if (!super.drain.get() || isClosed()) {
                return false;
            }
            int invalid = active.nextSetBit(this.downstreams.length);
            int missingHandle = active.nextSetBit(handles.length);
            if (invalid >= 0 || missingHandle >= 0) {
                return false;
            }

            int mIdx = 0;
            int[] mappings = new int[active.cardinality()];
            int[] activeIndexes = new int[this.downstreams.length];
            LatticeEdge[] nextHandles = new LatticeEdge[this.downstreams.length];
            Arrays.fill(activeIndexes, -1);
            for (int i = 0; i < this.downstreams.length; i++) {
                if (active.get(i)) {
                    LatticeEdge handle = Objects.requireNonNull(handles[i], "Active downstream handle");
                    activeIndexes[i] = mIdx;
                    mappings[mIdx++] = i;
                    nextHandles[i] = handle;
                    handle.setParent(this);
                }
            }

            RoutingState next = new RoutingState(mappings, activeIndexes, nextHandles);
            retainHandles(next);
            retired = (RoutingState) ROUTING_STATE.getAcquire(this);
            detached = detachedHandles(retired, next);
            Arrays.fill(this.downstreams, null);
            for (int physicalId : mappings) {
                this.downstreams[physicalId] = nextHandles[physicalId];
            }
            retired.retire();
            publishRoutingState(next);
        }
        releaseHandlesWhenQuiescent(retired);
        closeDetached(detached);
        return true;
    }

    /// Returns the active routing index for a physical downstream ID, or -1 when it is inactive.
    public final int getActiveDownstreamIndex(int downstreamId) {
        RoutingState state = (RoutingState) ROUTING_STATE.getAcquire(this);
        return state.getActiveDownstreamIndex(downstreamId);
    }

    public void setDrain(boolean value) {
        super.drain.setRelease(value);
    }

    void deferHandleRetirement(LatticeEdge handle, Runnable action) {
        Objects.requireNonNull(handle);
        Objects.requireNonNull(action);
        boolean runNow;
        synchronized (this.routingUpdateLock) {
            runNow = !this.handleReferences.containsKey(handle);
            if (!runNow) {
                this.handleRetirements
                        .computeIfAbsent(handle, ignored -> new ArrayList<>())
                        .add(action);
            }
        }
        if (runNow) {
            runRetirement(action);
        }
    }

    private RoutingState acquireRoutingState() {
        while (!(boolean) CLOSED.getAcquire(this)) {
            RoutingState state = (RoutingState) ROUTING_STATE.getAcquire(this);
            if (tryAcquireRoutingState(state)) {
                return state;
            }
        }
        return null;
    }

    boolean tryAcquireRoutingState(RoutingState state) {
        if (!state.tryAcquire()) {
            return false;
        }
        if (state == ROUTING_STATE.getAcquire(this) && !(boolean) CLOSED.getAcquire(this)) {
            return true;
        }
        releaseRoute(state);
        return false;
    }

    void publishRoutingState(RoutingState state) {
        ROUTING_STATE.setRelease(this, state);
    }

    private void releaseRoute(RoutingState state) {
        if (state.release() && state.isRetired()) {
            releaseHandlesWhenQuiescent(state);
        }
    }

    private void retainHandles(RoutingState state) {
        IdentityHashMap<LatticeEdge, Boolean> unique = new IdentityHashMap<>();
        for (int physicalId : state.mappings) {
            unique.put(state.handles[physicalId], Boolean.TRUE);
        }
        for (LatticeEdge handle : unique.keySet()) {
            this.handleReferences.merge(handle, 1, Integer::sum);
        }
    }

    private void releaseHandlesWhenQuiescent(RoutingState state) {
        if (!state.isRetired() || state.hasReaders() || !state.handlesReleased.compareAndSet(false, true)) {
            return;
        }

        LatticeEdge[] toClose;
        List<Runnable> retirements = new ArrayList<>();
        List<Runnable> terminalRetirements = List.of();
        synchronized (this.routingUpdateLock) {
            IdentityHashMap<LatticeEdge, Boolean> unique = new IdentityHashMap<>();
            for (int physicalId : state.mappings) {
                unique.put(state.handles[physicalId], Boolean.TRUE);
            }
            int closeCount = 0;
            for (LatticeEdge handle : unique.keySet()) {
                if (this.handleReferences.get(handle) == 1) {
                    closeCount++;
                }
            }
            toClose = new LatticeEdge[closeCount];
            int index = 0;
            for (LatticeEdge handle : unique.keySet()) {
                int references = this.handleReferences.get(handle);
                if (references == 1) {
                    this.handleReferences.remove(handle);
                    toClose[index++] = handle;
                    List<Runnable> actions = this.handleRetirements.remove(handle);
                    if (actions != null) {
                        retirements.addAll(actions);
                    }
                } else {
                    this.handleReferences.put(handle, references - 1);
                }
            }
            if ((boolean) CLOSED.getAcquire(this) && this.handleReferences.isEmpty() && !this.routingClosed) {
                this.routingClosed = true;
                terminalRetirements = new ArrayList<>(this.closeRetirements);
                this.closeRetirements.clear();
            }
        }
        closeDetached(toClose);
        retirements.forEach(this::runRetirement);
        terminalRetirements.forEach(this::runRetirement);
    }

    private LatticeEdge[] detachedHandles(RoutingState retired, RoutingState next) {
        IdentityHashMap<LatticeEdge, Boolean> retained = new IdentityHashMap<>();
        for (int physicalId : next.mappings) {
            retained.put(next.handles[physicalId], Boolean.TRUE);
        }
        int count = 0;
        for (LatticeEdge handle : this.downstreams) {
            if (handle != null && !contains(retired, handle) && !retained.containsKey(handle)) {
                count++;
            }
        }
        LatticeEdge[] detached = new LatticeEdge[count];
        int index = 0;
        for (LatticeEdge handle : this.downstreams) {
            if (handle != null && !contains(retired, handle) && !retained.containsKey(handle)) {
                detached[index++] = handle;
            }
        }
        return detached;
    }

    private static boolean contains(RoutingState state, LatticeEdge candidate) {
        for (int physicalId : state.mappings) {
            if (state.handles[physicalId] == candidate) {
                return true;
            }
        }
        return false;
    }

    private void closeDetached(LatticeEdge[] handles) {
        for (LatticeEdge handle : handles) {
            if (handle != null) {
                try {
                    handle.close();
                } catch (Throwable failure) {
                    this.logger.error("Failed to close a retired downstream edge", failure);
                }
            }
        }
    }

    private void runRetirement(Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            this.logger.error("Failed to retire a downstream receiver owner", failure);
        }
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
        if (!pushIfRoutable(frame)) {
            throw new IllegalStateException("Cannot route a frame without an active downstream");
        }
    }

    private boolean pushIfRoutable(AbstractFrame frame) {
        RoutingState state = acquireRoutingState();
        if (state == null) {
            return false;
        }
        try {
            if (state.mappings.length == 0) {
                return false;
            }
            push(frame, state);
            return true;
        } finally {
            releaseRoute(state);
        }
    }

    private void push(AbstractFrame frame, RoutingState state) {
        int mapLen = state.mappings.length;
        int logicalIdx = this.snapshotRoutingFunction.route(frame, mapLen, state);
        if (logicalIdx < 0 || logicalIdx >= mapLen) {
            throw new IllegalStateException("Routing function returned an invalid active index: " + logicalIdx);
        }
        int physicalId = state.mappings[logicalIdx];
        LatticeEdge downstream = state.handles[physicalId];
        if (downstream == null) {
            throw new IllegalStateException("Routing state has no handle for physical downstream " + physicalId);
        }
        downstream.push(frame);
    }

    @Override
    public void onError(Throwable throwable) {
        RoutingState state = acquireRoutingState();
        if (state == null) {
            return;
        }
        try {
            for (int physicalId : state.mappings) {
                state.handles[physicalId].onError(throwable);
            }
        } finally {
            releaseRoute(state);
        }
    }

    @Override
    public boolean isClosed() {
        return (boolean) CLOSED.getOpaque(this);
    }

    @Override
    public void close() {
        closeAfterRoutesRetire(() -> {});
    }

    /// Closes routing admission and invokes the action after all admitted route generations retire.
    public void closeAfterRoutesRetire(Runnable afterRoutesRetire) {
        Objects.requireNonNull(afterRoutesRetire);

        RoutingState retired = null;
        LatticeEdge[] detached = null;
        boolean runNow = false;
        synchronized (this.routingUpdateLock) {
            if ((boolean) CLOSED.getAcquire(this)) {
                if (this.routingClosed) {
                    runNow = true;
                } else {
                    this.closeRetirements.add(afterRoutesRetire);
                }
            } else if (CLOSED.compareAndSet(this, false, true)) {
                this.closeRetirements.add(afterRoutesRetire);
                retired = (RoutingState) ROUTING_STATE.getAcquire(this);
                int[] inactiveIndexes = new int[this.downstreams.length];
                Arrays.fill(inactiveIndexes, -1);
                RoutingState empty =
                        new RoutingState(new int[0], inactiveIndexes, new LatticeEdge[this.downstreams.length]);
                retired.retire();
                publishRoutingState(empty);
                detached = detachedHandles(retired, empty);
                Arrays.fill(this.downstreams, null);
            }
        }
        if (runNow) {
            runRetirement(afterRoutesRetire);
            return;
        }
        if (retired == null) {
            return;
        }
        super.close();
        releaseHandlesWhenQuiescent(retired);
        closeDetached(detached);
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

    @FunctionalInterface
    public interface SnapshotRoutingFunction {

        int route(AbstractFrame frame, int mapSize, RoutingState state);
    }

    public static final class RoutingState {

        private static final long RETIRED = Long.MIN_VALUE;

        public final int[] mappings;
        public final int[] activeIndexes;
        public final int mask;
        private final LatticeEdge[] handles;
        private final AtomicLong admission = new AtomicLong();
        private final AtomicBoolean handlesReleased = new AtomicBoolean();

        RoutingState(int[] mappings, int[] activeIndexes, LatticeEdge[] handles) {
            this.mappings = mappings;
            this.activeIndexes = activeIndexes;
            this.mask = mappings.length - 1;
            this.handles = handles;
        }

        public int getActiveDownstreamIndex(int downstreamId) {
            return downstreamId >= 0 && downstreamId < this.activeIndexes.length
                    ? this.activeIndexes[downstreamId]
                    : -1;
        }

        public LatticeEdge getDownstream(int downstreamId) {
            return downstreamId >= 0 && downstreamId < this.handles.length ? this.handles[downstreamId] : null;
        }

        private boolean tryAcquire() {
            long current = this.admission.getAcquire();
            while (current >= 0) {
                if (current == Long.MAX_VALUE) {
                    throw new IllegalStateException("Routing reader count overflow");
                }
                if (this.admission.compareAndSet(current, current + 1)) {
                    return true;
                }
                current = this.admission.getAcquire();
            }
            return false;
        }

        private void retire() {
            long current = this.admission.getAcquire();
            while (current >= 0 && !this.admission.compareAndSet(current, current | RETIRED)) {
                current = this.admission.getAcquire();
            }
        }

        private boolean release() {
            return this.admission.decrementAndGet() == RETIRED;
        }

        private boolean hasReaders() {
            return (this.admission.getAcquire() & Long.MAX_VALUE) != 0;
        }

        boolean isRetired() {
            return this.admission.getAcquire() < 0;
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
            ProductivityObservation observation = observation();
            if (LatticeVertex.this.pushIfRoutable(frame)) {
                observation.productive = true;
                return;
            }

            observation.productive = false;
            frame.doFinallyWithError(new IllegalStateException("Cannot route a frame without an active downstream"));
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
