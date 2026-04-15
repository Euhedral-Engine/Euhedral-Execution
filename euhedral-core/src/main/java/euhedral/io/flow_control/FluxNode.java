package euhedral.io.flow_control;

import static euhedral.io.utils.MathFunctions.unsignedMultiplyHigh;

import euhedral.io.flow_control.UpstreamQueue.UpstreamHandle;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.DrainBuffer;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import jdk.internal.vm.annotation.Contended;
import org.jctools.queues.MpscArrayQueue;
import org.jctools.util.PaddedAtomicLong;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FluxNode extends FluxEdge implements AutoCloseable {

    protected final boolean terminal;

    protected final Logger logger;
    protected final String name;
    protected final MpscArrayQueue<AbstractFrame> parallelQueue;

    @Contended
    protected final FluxEdge[] downstreams;
    protected final RoutingFunction routingFunction;
    private final PaddedAtomicLong wip;
    @Contended
    protected volatile RoutingState routingState = new RoutingState(new int[0]);

    protected long hash;
    protected int uniqueOrdered = 0;
    protected long xor1;
    protected long xor2;

    public FluxNode(String name, int downstreamCount) {
        this(name, downstreamCount, RoutingFunction.DEFAULT, false);
    }

    @SuppressWarnings("unchecked")
    public FluxNode(String name, int downstreamCount, RoutingFunction routingFunction,
            boolean terminal) {
        super(new AtomicBoolean(false));
        this.terminal = terminal;
        this.logger = LoggerFactory.getLogger(name);
        this.name = name;
        this.downstreams = new FluxEdge[downstreamCount];
        this.routingFunction = routingFunction;
        this.sibling = this;
        if (!terminal) {
            this.wip = new PaddedAtomicLong(0);
            this.parallelQueue = new MpscArrayQueue<>(2_048);
        } else {
            this.wip = null;
            this.parallelQueue = null;
        }
    }

    public void ingest(Publisher<? extends AbstractFrame> flux) {
        flux.subscribe(new UpstreamInterceptor());
    }

    public AtomicBoolean getDrainFlag() {
        return drain;
    }

    public boolean setDownstreamMapping(BitSet active,
            FluxEdge[] handles) {
        if (!drain.get()) {
            return false;
        }

        FluxEdge first = null;
        FluxEdge prev = null;
        FluxEdge curr = null;
        int mIdx = 0;
        int[] mappings = new int[active.cardinality()];
        for (int i = 0; i < downstreams.length; i++) {
            if (i < handles.length && handles[i] != null) {
                handles[i].index = i;
            }
            if (active.get(i)) {
                mappings[mIdx++] = i;
                handles[i].setParent(this);
                downstreams[i] = handles[i];

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

        this.routingState = new RoutingState(mappings);

        for (int i = 0; i < downstreams.length; i++) {
            if (!active.get(i) && downstreams[i] != null) {
                downstreams[i].close();
                downstreams[i] = null;
            }
        }
        return true;
    }

    public void setDrain(boolean value) {
        this.drain.set(value);
    }

    public boolean isDrained() {
        return parallelQueue == null || parallelQueue.isEmpty();
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        if (subscription instanceof FluxEdge dh) {
            super.onSubscribe(dh);
            dh.subscribe(this);
            for (var down : downstreams) {
                if (down != null) {
                    down.setParent(parent);
                }
            }
        } else if (subscription instanceof UpstreamInterceptor interceptor) {
            super.onSubscribe(interceptor);
        } else {
            UpstreamInterceptor interceptor = new UpstreamInterceptor();
            interceptor.onSubscribe(subscription);
        }
    }

    @Override
    public int getLayerWidth() {
        if (parent != null) {
            return parent.getLayerWidth();
        }
        return super.getLayerWidth();
    }

    @Override
    public void onNext(AbstractFrame frame) {
        RoutingState state = this.routingState;
        int mapLen = state.mappings.length;

        int logicalIdx = routingFunction.route(frame, mapLen);
        int id = state.mappings[logicalIdx];
        downstreams[id].onNext(frame);
    }

    @Override
    public void pull(DrainBuffer buffer, long demand) {
        if (demand <= 0 || buffer == null) {
            return;
        }

        if(parallelQueue != null && !parallelQueue.isEmpty()) {
            if (wip.compareAndSet(0, 1)) {
                try {
                    int count = parallelQueue.drain(buffer, (int) demand);
                    demand -= count;
                } finally {
                    wip.set(0);
                }
            }
        }

        FluxEdge parent = this.parent;
        if (parent != null) {
            parent.pull(buffer, demand);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        for (var down : downstreams) {
            if (down != null) {
                down.onError(throwable);
            }
        }
    }

    @Override
    public void onComplete() {

    }

    @Override
    public void close() {
        for (int i = 0; i < downstreams.length; i++) {
            if (downstreams[i] != null) {
                downstreams[i].close();
                downstreams[i] = null;
            }
        }
        parent = null;
    }

    @Override
    public long getHash() {
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        if (other instanceof FluxNode o) {
            return o.hash == hash;
        }
        return false;
    }

    @FunctionalInterface
    public interface RoutingFunction {

        RoutingFunction DEFAULT = (frame, mapSize) -> (int) unsignedMultiplyHigh(
                frame.getCombinedHash(), mapSize);

        int route(AbstractFrame frame, int mapSize);
    }

    protected static final class RoutingState {

        final int[] mappings;
        final int mask;
        final boolean isPow2;

        RoutingState(int[] mappings) {
            this.mappings = mappings;
            this.isPow2 = (mappings.length & (mappings.length - 1)) == 0;
            this.mask = mappings.length - 1;
        }
    }

    @Contended
    public class UpstreamInterceptor extends UpstreamHandle implements Subscriber<AbstractFrame> {

        private final PaddedAtomicLong wip = new PaddedAtomicLong(0);
        private final PaddedAtomicLong demand = new PaddedAtomicLong(0);

        public Subscription upstream;
        public volatile boolean complete = false;
        private long count = 0;
        private volatile FluxEdge owner = null;
        private volatile FluxEdge fastPath = null;

        @Override
        public void onSubscribe(@NonNull Subscription subscription) {
            this.upstream = subscription;
            FluxNode.this.onSubscribe(this);
        }

        @Override
        public void onNext(AbstractFrame frame) {
            if ((count++ & 63) == 0) {
                frame.setIngestNs(System.nanoTime());
            } else {
                frame.setIngestNs(0);
            }

            if (!frame.isOrdered()) {
                if (parallelQueue != null && parallelQueue.relaxedOffer(frame)) {
                    return;
                }
            }

            FluxNode.this.onNext(frame);
        }

        @Override
        public void pull(DrainBuffer buffer, long demand) {
            FluxNode.this.pull(buffer, demand);
        }

        @Override
        public void request(long num) {
            if (num <= 0 || drain.get()) {
                return;
            }

            if (wip.compareAndSet(0, 1)) {
                try {
                    long demand = addAndReset(num);
                    upstream.request(demand);
                } finally {
                    wip.set(0);
                }
                return;
            }
            demand.accumulateAndGet(num, UpstreamInterceptor::addCap);
            if (wip.compareAndSet(0, 1)) {
                try {
                    long d = addAndReset(0);
                    upstream.request(d);
                } finally {
                    wip.set(0);
                }
            }
        }

        private static long addCap(long num1, long num2) {
            if (num1 < 0 || num2 < 0) {
                return Long.MAX_VALUE;
            }
            long sum = num1 + num2;
            return sum < 0 ? Long.MAX_VALUE : sum;
        }

        private long addAndReset(long num) {
            long sum = demand.getAndSet(0);
            sum += num;
            if (sum < 0) {
                return Long.MAX_VALUE;
            }
            return sum;
        }

        @Override
        public boolean setFastPath(FluxEdge edge) {
            if (fastPath == null) {
                owner = edge;
                return true;
            }
            return false;
        }

        @Override
        public void onError(Throwable throwable) {
            logger.error("UpstreamHandle Error", throwable);
            this.complete = true;
        }

        @Override
        public void onComplete() {
            logger.debug("UpstreamHandle Complete");
            this.complete = true;
        }

        @Override
        public void cancel() {
            logger.debug("UpstreamHandle Cancelled");
            complete = true;
            upstream.cancel();
        }

        @Override
        public boolean isComplete() {
            return complete;
        }
    }
}
