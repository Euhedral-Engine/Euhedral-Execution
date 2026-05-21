package euhedral.io.flow_control;

import static euhedral.io.utils.MathFunctions.unsignedMultiplyHigh;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.io.flow_control.UpstreamQueue.UpstreamHandle;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.RecursiveScaffolding;
import euhedral.io.generics.ScaffoldingSource;
import euhedral.io.utils.DrainBuffer;
import euhedral.queues.PartitionedMpscArrayQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("unused")
public class ScaffoldingNode extends ScaffoldingEdge implements AutoCloseable {

    protected static final VarHandle ROUTING_STATE;

    static {
        try {
            ROUTING_STATE = MethodHandles.lookup()
                    .findVarHandle(ScaffoldingNode.class, "routingState", RoutingState.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    protected final boolean terminal;

    protected final Logger logger;
    protected final String name;
    protected final PartitionedMpscArrayQueue<AbstractFrame> parallelQueue;

    protected final ScaffoldingEdge[] downstreams;
    protected final RoutingFunction routingFunction;
    private final PaddedAtomicLong wip;

    protected RoutingState routingState = new RoutingState(new int[0]);

    public ScaffoldingNode(String name, int downstreamCount) {
        this(name, downstreamCount, RoutingFunction.DEFAULT, false);
    }

    public ScaffoldingNode(String name, int downstreamCount, RoutingFunction routingFunction,
            boolean terminal) {
        super(new AtomicBoolean(false));
        this.terminal = terminal;
        this.logger = LoggerFactory.getLogger(name);
        this.name = name;
        this.downstreams = new ScaffoldingEdge[downstreamCount];
        this.routingFunction = routingFunction;
        this.sibling = this;
        if (!terminal) {
            this.wip = new PaddedAtomicLong(0);
            this.parallelQueue = new PartitionedMpscArrayQueue<>(2_048);
        } else {
            this.wip = null;
            this.parallelQueue = null;
        }
    }

    public void ingest(ScaffoldingSource stream) {
        UpstreamInterceptor interceptor = new UpstreamInterceptor();
        stream.addDownstream(interceptor);
    }

    public AtomicBoolean getDrainFlag() {
        return this.drain;
    }

    public boolean setDownstreamMapping(BitSet active,
            ScaffoldingEdge[] handles) {
        if (!this.drain.get()) {
            return false;
        }

        ScaffoldingEdge first = null;
        ScaffoldingEdge prev = null;
        ScaffoldingEdge curr = null;
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

    @Override
    public void addUpstream(RecursiveScaffolding scaffolding) {
        if (scaffolding instanceof ScaffoldingEdge dh) {
            super.addUpstream(dh);
            dh.addDownstream(this);
            for (var down : this.downstreams) {
                if (down != null) {
                    ScaffoldingEdge parent = (ScaffoldingEdge) PARENT.getOpaque(this);
                    down.setParent(parent);
                }
            }
        } else if (scaffolding instanceof UpstreamHandle interceptor) {
            super.addUpstream(interceptor);
        }
    }

    @Override
    public void onNext(AbstractFrame frame) {
        RoutingState state = (RoutingState) ROUTING_STATE.getOpaque(this);
        int mapLen = state.mappings.length;

        int logicalIdx = this.routingFunction.route(frame, mapLen);
        int id = state.mappings[logicalIdx];
        this.downstreams[id].onNext(frame);
    }

    @Override
    public void pull(DrainBuffer buffer, long demand) {
        if (demand <= 0 || buffer == null) {
            return;
        }

        if (this.parallelQueue != null && !this.parallelQueue.isEmpty()) {
            if (this.wip.compareAndSet(0, 1)) {
                try {
                    int count = this.parallelQueue.drain(buffer::accept, (int) demand);
                    demand -= count;
                } finally {
                    this.wip.set(0);
                }
            }
        }

        ScaffoldingEdge parent = (ScaffoldingEdge) PARENT.getOpaque(this);
        if (parent != null) {
            parent.pull(buffer, demand);
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

    @FunctionalInterface
    public interface RoutingFunction {

        RoutingFunction DEFAULT = (frame, mapSize) -> (int) unsignedMultiplyHigh(
                frame.getCombinedHash(), mapSize);

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
        public ScaffoldingSource upstream;
        private long count = 0;

        @Override
        public void addUpstream(@NonNull ScaffoldingSource upstream) {
            this.upstream = upstream;
            ScaffoldingNode.this.addUpstream(this);
        }

        @Override
        public void onNext(AbstractFrame frame) {
            if ((this.count++ & 63) == 0) {
                frame.setIngestNs(System.nanoTime());
            } else {
                frame.setIngestNs(0);
            }

            if (!frame.isOrdered()) {
                if (ScaffoldingNode.this.parallelQueue != null && ScaffoldingNode.this.parallelQueue.offer(
                        frame)) {
                    return;
                }
            }

            ScaffoldingNode.this.onNext(frame);
        }

        @Override
        public void pull(DrainBuffer buffer, long demand) {
            ScaffoldingNode.this.pull(buffer, demand);
        }

        @Override
        public void request(long num) {
            if (num <= 0 || ScaffoldingNode.this.drain.getOpaque() || this.complete.getOpaque()) {
                return;
            }

            if (this.wip.compareAndSet(0, 1)) {
                try {
                    long demand = addAndReset(num);
                    this.upstream.request(demand);
                } catch (Throwable t) {
                    logger.error("Upstream threw an exception during request", t);
                    cancel();
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
                    cancel();
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
        public void cancel() {
            if (this.complete.compareAndSet(false, true)) {
                logger.trace("UpstreamHandle Cancelled");
                this.upstream.cancel();
            }
        }

        @Override
        public boolean isComplete() {
            return this.complete.getOpaque();
        }
    }
}
