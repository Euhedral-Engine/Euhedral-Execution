package euhedral.io.flow_control;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.QueueFrame;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder;
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
    protected final FlowRecorder fillBytesRecorder;
    @Getter
    protected final FlowRecorder drainRecorder;
    @Getter
    protected final FlowRecorder drainBytesRecorder;

    protected final AtomicLong wip = new AtomicLong(0);
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
        this.fillBytesRecorder = new FlowRecorder();
        this.drainRecorder = new FlowRecorder();
        this.drainBytesRecorder = new FlowRecorder();
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

    public long drain(DrainBuffer drainBuffer, int maxFill, long demand) {
        if (maxFill <= 0) {
            hookOnDrain(demand);
            return 0;
        }
        drainBuffer.reset();

        int cycles = 0;

        long totalDrain = 0;
        long totalBytesDrained = 0;
        long totalQueueWeight = 0;

        long initialCount = totalCount.get();
        for (int i = 0; i < maxFill && cycles <= queueRing.length && initialCount > 0; ) {
            QueueFrame queue = queueRing[head];

            int quota = (int) queue.getQuota();
            if (quota <= 0) {
                quota = refillQueueQuota(queue);
            }
            quota = (int) Math.min(quota, maxFill - totalDrain);

            drainBuffer.drainCount = 0;
            drainBuffer.drainedBytes = 0;
            int drainCount = queue.drain(drainBuffer, quota);
            long drainedBytes = drainBuffer.drainedBytes;

            if (drainCount > 0) {
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
            totalCount.getAndAdd(-totalDrain);
            totalQueuedSizeBytes.getAndAdd(-totalBytesDrained);
            this.totalQueueWeight = totalQueueWeight;
        }
        drainBuffer.drainCount = 0;
        drainBuffer.drainedBytes = 0;
        if (totalDrain < maxFill) {
            pull(drainBuffer, maxFill - totalDrain);
        }
        long now = System.nanoTime();
        drainRecorder.record(now, totalDrain + drainBuffer.drainCount);
        drainBytesRecorder.record(now, totalBytesDrained + drainBuffer.drainedBytes);
        hookOnDrain(demand);

        if(drainBuffer.uniqueOrdered > this.uniqueOrdered) {
            uniqueOrdered = drainBuffer.uniqueOrdered;
            xor1 = drainBuffer.xor1;
            xor2 = drainBuffer.xor2;
        }
        totalDrain += drainBuffer.drainCount;
        drainBuffer.reset();
        return totalDrain;
    }

    protected void hookOnDrain(long demand) {
        request(demand);
    }

    protected int refillQueueQuota(QueueFrame queue) {
        return Integer.MAX_VALUE;
    }

    protected void recordDrainMetrics(QueueFrame queue, long drainCount) {

    }

    public long getMaxQueuedBytes() {
        return Long.MAX_VALUE;
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

    public static final class WakeHook {

        private final Thread cycleThread;
        public volatile boolean parked = false;

        public WakeHook(Thread cycleThread) {
            this.cycleThread = cycleThread;
        }

        public void wake() {
            if (parked) {
                LockSupport.unpark(cycleThread);
            }
        }
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
                while(!wip.compareAndSet(0, 1)) {
                    Thread.onSpinWait();
                }
                try {
                    long now = System.nanoTime();
                    fillRecorder.record(now, 64);
                    fillBytesRecorder.record(now, size);
                } finally {
                    wip.set(0);
                }
            }

            if (wakeHook != null) {
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
}
