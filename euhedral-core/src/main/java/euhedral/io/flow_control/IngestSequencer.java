package euhedral.io.flow_control;

import static euhedral.io.utils.MathFunctions.clampDouble;

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
import org.jctools.util.PaddedAtomicLong;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public abstract class IngestSequencer extends FluxNode implements AutoCloseable {

    protected final int chunkSize;
    protected final double smoothingFactor;

    protected final QueueFrame[] queueRing;
    protected final QueueStats[] queueStats;
    protected final int mask;

    @Getter
    protected final FlowRecorder fillRecorder;
    @Getter
    protected final FlowRecorder fillBytesRecorder;
    @Getter
    protected final FlowRecorder drainRecorder;
    @Getter
    protected final FlowRecorder drainBytesRecorder;

    protected final AtomicLong totalCount = new AtomicLong(0);
    protected final AtomicLong totalQueuedSizeBytes = new AtomicLong(0);
    protected long totalQueueWeight = 0;

    protected int head = 0;
    @Setter
    protected WakeHook wakeHook;

    public IngestSequencer(String name, int subQueues, int chunkSize) {
        super(name, getSubQueueCount(subQueues), RoutingFunction.DEFAULT, true);

        double dt = 0.1;
        double tau = 2.0; // 2 Seconds
        double smoothingFactor = 1.0 - Math.exp(-dt / tau);

        if (!Double.isFinite(smoothingFactor) || smoothingFactor <= 0) {
            this.smoothingFactor = 0.0645; // Fallback to 1 - e^(-0.2/3.0)
        } else {
            this.smoothingFactor = clampDouble(smoothingFactor, 0.01, 1.0);
        }
        int queueCount = getSubQueueCount(subQueues);

        this.chunkSize = chunkSize;
        this.fillRecorder = new FlowRecorder();
        this.fillBytesRecorder = new FlowRecorder();
        this.drainRecorder = new FlowRecorder();
        this.drainBytesRecorder = new FlowRecorder();
        this.queueRing = new QueueFrame[queueCount];
        this.queueStats = new QueueStats[queueCount];
        this.mask = queueCount - 1;

        BitSet mappings = new BitSet(queueCount);
        mappings.set(0, queueCount);
        FluxEdge[] queueHandles = new FluxEdge[queueCount];

        for (int i = 0; i < queueCount; i++) {
            queueStats[i] = new QueueStats(i);
            QueueFrame queue = new QueueFrame(0, new MpscUnboundedXaddArrayQueue<>(chunkSize, 2));
            queueRing[i] = queue;
            queueHandles[i] = new FluxEdge(super.drain);
            queueHandles[i].subscribe(new QueueSubscriber(i));
        }
        setDrain(true);
        super.setDownstreamMapping(mappings, queueHandles);
        setDrain(false);
    }

    protected static int getSubQueueCount(int maxQueues) {
        if (maxQueues <= 1) {
            return 1;
        } else {
            return Integer.highestOneBit((maxQueues - 1) << 1);
        }
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
        for (int i = 0; i < maxFill && cycles <= queueRing.length && initialCount > 0;) {
            QueueFrame queue = queueRing[head];
            QueueStats stats = queueStats[head];

            int quota = (int) stats.quotaBytes;
            if (quota <= 0) {
                refillQueueQuota(stats);
                quota = (int) stats.quotaBytes;
            }
            quota = (int) Math.min(quota, maxFill - totalDrain);

            int drainCount = queue.drain(drainBuffer, quota);
            long drainedBytes = drainBuffer.drainedBytes;

            if (drainCount > 0) {
                i += drainCount;
                totalBytesDrained += drainedBytes;
                totalDrain += drainCount;
                totalQueueWeight += stats.weight;
                stats.drainCycles++;
                stats.quotaBytes -= drainedBytes;
                stats.lastBytesDrained = drainedBytes;

                recordDrainMetrics(queue, stats, drainCount);
                cycles = 0;
            } else {
                cycles++;
            }

            head = (head + 1) & mask;
        }
        if (totalDrain > 0) {
            totalCount.getAndAdd(-totalDrain);
            totalQueuedSizeBytes.getAndAdd(-totalBytesDrained);
            this.totalQueueWeight = totalQueueWeight;
        }
        drainBuffer.reset();
        if (totalDrain < maxFill) {
            pull(drainBuffer, maxFill - totalDrain);
        }
        long now = System.nanoTime();
        drainRecorder.record(now, totalDrain + drainBuffer.drainCount, true);
        drainBytesRecorder.record(now, totalBytesDrained + drainBuffer.drainedBytes, true);
        hookOnDrain(demand);

        totalDrain += drainBuffer.drainCount;
        drainBuffer.reset();
        return totalDrain;
    }

    protected void hookOnDrain(long demand) {
        request(demand);
    }

    protected void refillQueueQuota(QueueStats stats) {
        stats.quotaBytes = Integer.MAX_VALUE;
    }

    protected void recordDrainMetrics(QueueFrame queue, QueueStats stats, long drainCount) {

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
            long adjustedSize = size <= 0 ? 256 : size;
            queueStats[idx].avgFrameSize.getAndUpdate(
                    curr -> (long) ((1 - smoothingFactor) * curr + smoothingFactor * adjustedSize));

            totalQueuedSizeBytes.addAndGet(adjustedSize);
            long count = totalCount.incrementAndGet();
            if ((count & 63) == 0) {
                long now = System.nanoTime();
                fillRecorder.record(now, 64, true);
                fillBytesRecorder.record(now, adjustedSize, true);
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

    protected static class QueueStats {

        public final int index;
        public final PaddedAtomicLong avgFrameSize = new PaddedAtomicLong(1024);

        public long weight = 1024;
        public long drainCycles = 0;
        public long lastBytesDrained = 0;
        public long quotaBytes = 0;

        public QueueStats(int index) {
            this.index = index;
        }

        public void reset() {
            avgFrameSize.set(1024);
            weight = 1024;
            drainCycles = 0;
            lastBytesDrained = 0;
            quotaBytes = 0;
        }
    }
}
