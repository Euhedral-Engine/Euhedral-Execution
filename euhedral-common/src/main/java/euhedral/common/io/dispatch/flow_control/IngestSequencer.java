package euhedral.common.io.dispatch.flow_control;

import euhedral.common.io.dispatch.flow_control.DemandCoordinator.FluxEdge;
import euhedral.common.io.dispatch.frames.AbstractFrame;
import euhedral.common.io.dispatch.frames.QueueFrame;
import euhedral.common.io.dispatch.utils.FlowRecorder;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import lombok.Setter;
import org.jctools.queues.MpscUnboundedXaddArrayQueue;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public abstract class IngestSequencer extends FluxNode implements AutoCloseable {

    protected final double smoothingFactor;

    protected final QueueFrame[] queueRing;
    protected final int mask;

    @Getter
    protected final FlowRecorder fillRecorder;
    @Getter
    protected final FlowRecorder bytesPerBatchRecorder;
    @Getter
    protected final FlowRecorder batchRecorder;

    protected final AtomicLong totalCount = new AtomicLong(0);
    protected final AtomicLong totalQueuedSizeBytes = new AtomicLong(0);
    protected long totalQueueWeight = 0;

    protected int head = 0;
    @Setter
    protected WakeHook wakeHook;

    public IngestSequencer(String name, int maxConnections) {
        int connectionCapacity;
        if (maxConnections <= 1) {
            connectionCapacity = 1;
        } else {
            connectionCapacity = Integer.highestOneBit((maxConnections - 1) << 1);
        }

        super(name, connectionCapacity, RoutingFunction.DEFAULT, true);

        double dt = 0.1;
        double tau = 2.0; // 2 Seconds
        double smoothingFactor = 1.0 - Math.exp(-dt / tau);

        if (!Double.isFinite(smoothingFactor) || smoothingFactor <= 0) {
            this.smoothingFactor = 0.0645; // Fallback to 1 - e^(-0.2/3.0)
        } else {
            this.smoothingFactor = Math.clamp(smoothingFactor, 0.01, 1.0);
        }

        this.fillRecorder = new FlowRecorder();
        this.bytesPerBatchRecorder = new FlowRecorder();
        this.batchRecorder = new FlowRecorder();
        this.queueRing = new QueueFrame[connectionCapacity];
        this.mask = connectionCapacity - 1;

        BitSet mappings = new BitSet(connectionCapacity);
        mappings.set(0, connectionCapacity);
        FluxEdge[] queueHandles = new FluxEdge[connectionCapacity];

        for (int i = 0; i < connectionCapacity; i++) {
            QueueFrame queue = new QueueFrame(0, smoothingFactor,
                    new MpscUnboundedXaddArrayQueue<>(8192));
            queueRing[i] = queue;
            queueHandles[i] = new FluxEdge(super.drain);
            queueHandles[i].subscribe(new QueueSubscriber(i));
        }
        setDrain(true);
        super.setDownstreamMapping(mappings, queueHandles);
        setDrain(false);
    }

    @Override
    public boolean setDownstreamMapping(BitSet active, FluxEdge[] edges) {
        return false;
    }

    public int drain(QueueFrame.DrainBuffer drainBuffer, int maxFill, long demand) {
        if (maxFill <= 0) {
            return 0;
        }

        int cycles = 0;

        int totalDrain = 0;
        long totalBytesDrained = 0;
        long totalQueueWeight = 0;
        for (int i = 0; i < maxFill && cycles <= queueRing.length; ) {
            QueueFrame queue = queueRing[head];

            int quota = (int) queue.getQuota();
            if(quota <= 0) {
                refillQueueQuota(queue);
                quota = (int) queue.getQuota();
            }

            int drainCount = queue.drain(drainBuffer, quota);
            long drainedBytes = drainBuffer.drainedBytes;

            if(drainCount > 0) {
                i += drainCount;
                totalBytesDrained += drainedBytes;
                totalDrain += drainCount;
                totalQueueWeight += queue.getWeight();

                recordDrainMetrics(queue, drainCount);
                cycles = 0;
            } else {
                cycles++;
            }

            head = (head + 1) & mask;
        }
        if(totalDrain > 0) {
            long now = System.nanoTime();
            totalCount.getAndAdd(-totalDrain);
            totalQueuedSizeBytes.getAndAdd(-totalBytesDrained);
            this.totalQueueWeight = totalQueueWeight;
            bytesPerBatchRecorder.record(now, totalBytesDrained);
            batchRecorder.record(now, totalDrain);
        }
        hookOnDrain(demand);
        return totalDrain;
    }

    protected abstract void hookOnDrain(long demand);

    protected abstract int refillQueueQuota(QueueFrame queue);

    protected long getMaxQueueCount() {
        return Long.MAX_VALUE;
    }

    protected void recordDrainMetrics(QueueFrame queue, long drainCount) {

    }

    public long getCount() {
        return totalCount.get();
    }

    public boolean isEmpty() {
        return totalCount.get() <= 0;
    }

    @Override
    public void close() {
        super.close();
    }

    protected class QueueSubscriber implements Subscriber<AbstractFrame> {

        private final int idx;

        public QueueSubscriber(int idx) {
            this.idx = idx;
        }

        @Override
        public void onNext(AbstractFrame frame) {
            while (!queueRing[idx].enqueue(frame)) {
                Thread.onSpinWait();
            }

            long size = frame.getSizeBytes();
            size = size <= 0 ? 256 : size;

            totalQueuedSizeBytes.addAndGet(size);
            long count = totalCount.incrementAndGet();
            if((count & 63) == 0) {
                long now = System.nanoTime();
                fillRecorder.record(now, 64);
            }
            if(wakeHook != null) {
                wakeHook.wake();
            }
        }

        @Override
        public void onSubscribe(Subscription subscription) {

        }

        @Override
        public void onError(Throwable throwable) {

        }

        @Override
        public void onComplete() {

        }
    }

    protected class QueueRouting implements RoutingFunction {
        volatile int queueCount = 0;

        @Override
        public int route(AbstractFrame frame, int mapSize) {
            return (int) Math.unsignedMultiplyHigh(frame.getCombinedHash(), queueCount);
        }
    }

    public static final class WakeHook {
        private final Thread cycleThread;
        public volatile boolean parked = false;

        public WakeHook(Thread cycleThread) {
            this.cycleThread = cycleThread;
        }

        public void wake() {
            if(parked) {
                LockSupport.unpark(cycleThread);
            }
        }
    }
}
