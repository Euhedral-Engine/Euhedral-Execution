package euhedral.common.io.dispatch.frames;

import euhedral.common.io.dispatch.utils.FlowRecorder;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.Setter;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.SpscUnboundedArrayQueue;
import org.jspecify.annotations.NonNull;

public class QueueFrame extends AbstractFrame implements AutoCloseable {

    protected final long idHash;
    protected final MessagePassingQueue<AbstractFrame> queue;
    protected final double smoothingFactor;
    @Getter
    protected final AtomicLong avgFrameSize = new AtomicLong(1024);
    @Getter
    @Setter
    protected volatile int index = -1;
    @Getter
    protected long weight = 1024;

    @Getter
    protected long drainCycles = 0;

    @Getter
    protected long lastBytesDrained = 0;

    @Getter
    @Setter
    protected long quota = 0;

    public QueueFrame(long idHash) {
        this(idHash, 0.1);
    }

    public QueueFrame(long idHash, double smoothingFactor) {
        this(idHash, smoothingFactor, new SpscUnboundedArrayQueue<>(4096));
    }

    public QueueFrame(long idHash, double smoothingFactor,
            MessagePassingQueue<AbstractFrame> queue) {
        super(idHash, 0, null);
        this.idHash = idHash;
        this.smoothingFactor = smoothingFactor;
        this.queue = queue;
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public boolean enqueue(AbstractFrame frame) {
        if (queue.relaxedOffer(frame)) {
            avgFrameSize.getAndUpdate(curr ->
                    (long) ((1 - smoothingFactor) * curr + smoothingFactor * frame.getSizeBytes())
            );
            return true;
        }
        return false;
    }

    public int drain(@NonNull DrainBuffer drainBuffer, int limit) {
        if (limit <= 0) {
            return 0;
        }

        drainBuffer.drainCount = 0;
        drainBuffer.drainedBytes = 0;
        int count = this.queue.drain(drainBuffer, limit);
        if (count > 0) {
            drainCycles++;
        }

        return count;
    }

    public long smoothWeight(long target, double cv) {
        double stepPercent = 0.25 - (cv * 0.5);
        if (stepPercent < 0.05) {
            stepPercent = 0.05;
        } else if (stepPercent > 0.25) {
            stepPercent = 0.25;
        }

        long maxStep = (long) (weight * stepPercent);
        long delta = target - weight;

        if (delta > maxStep) {
            target = weight + maxStep;
        } else if (delta < -maxStep) {
            target = weight - maxStep;
        }

        weight = (long) (weight * 0.9 + target * 0.1);
        return weight;
    }

    public int getQueueCount() {
        return queue.size();
    }

    @Override
    public String getId() {
        return idHash + "";
    }

    @Override
    public long getSizeBytes() {
        long size = queue.size() * avgFrameSize.get();
        return size < 0 ? Long.MAX_VALUE : size;
    }

    @Override
    public boolean isAlive() {
        return true;
    }

    @Override
    public void kill() {
        close();
    }

    @Override
    public void close() {
        AbstractFrame frame;
        while ((frame = queue.poll()) != null) {
            frame.kill();
        }
        queue.clear();
    }

    @Override
    public void doFinally() {
        close();
    }

    public QueueFrame clone(long idHash) {
        return new QueueFrame(idHash, smoothingFactor);
    }

    public static class DrainBuffer implements MessagePassingQueue.Consumer<AbstractFrame> {

        public final MessagePassingQueue<AbstractFrame> buffer;

        public final FlowRecorder arrivalLatencyRecorder = new FlowRecorder();

        public long drainCount = 0;
        public long drainedBytes = 0;

        public DrainBuffer(MessagePassingQueue<AbstractFrame> buffer) {
            this.buffer = buffer;
        }

        @Override
        public void accept(AbstractFrame frame) {
            while (!buffer.relaxedOffer(frame)) {
                Thread.onSpinWait();
            }
            if(frame.getIngestNs() > 0) {
                long now = System.nanoTime();
                arrivalLatencyRecorder.record(now, now - frame.getIngestNs());
            }
            drainCount++;
            drainedBytes += frame.getSizeBytes();
        }
    }
}

