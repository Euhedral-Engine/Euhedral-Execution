package euhedral.common.io.dispatch.flow_control;

import euhedral.common.io.dispatch.flow_control.DemandCoordinator.FluxEdge;
import euhedral.common.io.dispatch.flow_control.DemandCoordinator.UpstreamHandle;
import euhedral.common.io.dispatch.frames.AbstractFrame;
import euhedral.common.io.dispatch.utils.KeyHasher;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.BitSet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import jdk.internal.vm.annotation.Contended;
import org.jctools.queues.MpmcUnboundedXaddArrayQueue;
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
    protected final long hash = KeyHasher.mix(ThreadLocalRandom.current().nextLong());

    protected final MpmcUnboundedXaddArrayQueue<UpstreamInterceptor> pendingUpstreams = new MpmcUnboundedXaddArrayQueue<>(
            128);

    @Contended
    protected final FluxEdge[] downstreams;
    protected final RoutingFunction routingFunction;
    protected final AtomicBoolean drain;

    @Contended
    protected volatile int[] mappings = new int[0];

    protected volatile long[] masks = new long[0];

    public FluxNode(String name, int downstreamCount) {
        this(name, downstreamCount, RoutingFunction.DEFAULT, false);
    }

    @SuppressWarnings("unchecked")
    public FluxNode(String name, int downstreamCount, RoutingFunction routingFunction, boolean terminal) {
        AtomicBoolean drain = new AtomicBoolean(false);
        super(drain);
        this.terminal = terminal;
        this.drain = drain;
        this.logger = LoggerFactory.getLogger(name);
        this.name = name;
        this.downstreams = new FluxEdge[downstreamCount];
        this.routingFunction = routingFunction;
        this.sibling = this;
    }

    public void ingest(Publisher<AbstractFrame> flux) {
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

        LockSupport.parkNanos(1_000);


        FluxEdge first = null;
        FluxEdge prev = null;
        FluxEdge curr = null;
        int[] nextMappings = new int[active.cardinality()];
        int mIdx = 0;
        for (int i = 0; i < downstreams.length; i++) {
            if(i < handles.length && handles[i] != null) {
                handles[i].index = i;
            }
            if (active.get(i)) {

                nextMappings[mIdx++] = i;
                handles[i].setParent(this);
                downstreams[i] = handles[i];

                if(curr == null) {
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
        if(curr != null) {
            curr.sibling = first;
        }

        this.mappings = nextMappings;

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

    public void drainPending() {
        while (!pendingUpstreams.isEmpty()) {
            UpstreamInterceptor temp = pendingUpstreams.relaxedPoll();
            if (temp == null) {
                continue;
            }
            for (var down : downstreams) {
                if (down != null) {
                    down.onSubscribe(temp);
                }
            }
        }
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
    public void onSubscribe(Subscription subscription) {
        if (subscription instanceof FluxEdge dh) {
            super.onSubscribe(dh);
            dh.subscribe(this);
            for (var down : downstreams) {
                if (down != null) {
                    down.setParent(parent);
                }
            }
        } else if(subscription instanceof UpstreamInterceptor interceptor) {
            super.onSubscribe(interceptor);
        } else {
            UpstreamInterceptor interceptor = new UpstreamInterceptor();
            interceptor.onSubscribe(subscription);
        }
    }

    @Override
    public void onNext(AbstractFrame frame) {
        drainSpin();

        long upCount = getUpstreamCount();
        int mapLen = mappings.length;

        if(!terminal && upCount < (mapLen >>> 1) && !frame.isOrdered()) {
            int index;
            // Check if power of 2
            if(((mapLen - 1) & mapLen) == 0) {
                index = (int) frame.getIdHash() & (mapLen - 1);
            } else {
                index = (int) Math.unsignedMultiplyHigh(frame.getIdHash(), mapLen);
            }
            downstreams[mappings[index]].onNext(frame);
            return;
        }

        int logicalIdx = routingFunction.route(frame, mapLen);
        int id = mappings[logicalIdx];

        downstreams[id].onNext(frame);
    }

    private void drainSpin() {
        int cycles = 0;
        while (drain.get()) {
            if (++cycles < 128) {
                Thread.onSpinWait();
            } else if (cycles < 256) {
                Thread.yield();
            } else {
                LockSupport.parkNanos(10_000);
                cycles = 0;
            }
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
    public boolean equals(Object other) {
        if(other instanceof FluxNode o) {
            return o.hash == hash;
        }
        return false;
    }

    @FunctionalInterface
    public interface RoutingFunction {
        RoutingFunction DEFAULT = (frame, mapSize) -> (int) Math.unsignedMultiplyHigh(frame.getCombinedHash(), mapSize);
        int route(AbstractFrame frame, int mapSize);
    }

    @Contended
    public class UpstreamInterceptor extends UpstreamHandle implements Subscriber<AbstractFrame> {

        public Subscription upstream;
        public volatile boolean complete = false;

        @Override
        public void onSubscribe(@NonNull Subscription subscription) {
            this.upstream = subscription;
            FluxNode.this.onSubscribe(this);
        }

        @Override
        public void onNext(AbstractFrame frame) {
            FluxNode.this.onNext(frame);
        }

        @Override
        public void request(long num) {
            if (num <= 0) {
                return;
            }
            upstream.request(num);
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
