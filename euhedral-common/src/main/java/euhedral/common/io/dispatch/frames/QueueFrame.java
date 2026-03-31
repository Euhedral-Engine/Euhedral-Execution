package euhedral.common.io.dispatch.frames;

import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.Setter;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.SpscUnboundedArrayQueue;
import org.jctools.util.PaddedAtomicLong;
import org.jspecify.annotations.NonNull;

public class QueueFrame extends AbstractFrame implements AutoCloseable {

    protected final long idHash;
    protected final MessagePassingQueue<AbstractFrame> queue;
    protected final PaddedAtomicLong queueCount = new PaddedAtomicLong(0);
    protected final AtomicLong queueSizeBytes = new AtomicLong();
    protected final double smoothingFactor;
    @Getter
    protected final AtomicLong avgFrameSize = new AtomicLong(1024);
    @Getter
    @Setter
    protected volatile int index = -1;
    @Getter
    protected long weight = 1024;

    @Getter
    protected long drainCount = 0;

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
        return queueCount.get() == 0;
    }

    public boolean enqueue(AbstractFrame frame) {
        if (queue.relaxedOffer(frame)) {
            queueCount.incrementAndGet();
            queueSizeBytes.addAndGet(frame.getSizeBytes());
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

        int count = this.queue.drain(drainBuffer, limit);
        if (count > 0) {
            queueCount.addAndGet(-count);
            queueSizeBytes.addAndGet(-drainBuffer.drainedBytes);
            drainBuffer.drainedBytes = 0;
            drainCount++;
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
        return queueSizeBytes.get();
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

        public long drainedBytes = 0;

        public DrainBuffer(MessagePassingQueue<AbstractFrame> buffer) {
            this.buffer = buffer;
        }

        @Override
        public void accept(AbstractFrame frame) {
            while (!buffer.relaxedOffer(frame)) {
                Thread.onSpinWait();
            }
            drainedBytes += frame.getSizeBytes();
        }
    }
}

