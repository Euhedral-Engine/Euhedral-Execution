package euhedral.io.frames;

import euhedral.io.utils.DrainBuffer;
import java.util.concurrent.atomic.AtomicLong;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.SpscUnboundedArrayQueue;
import org.jspecify.annotations.NonNull;

public class QueueFrame extends AbstractFrame implements AutoCloseable {

    protected final long idHash;
    protected final MessagePassingQueue<AbstractFrame> queue;
    protected final AtomicLong sizeBytes = new AtomicLong(0);

    public QueueFrame(long idHash) {
        this(idHash, new SpscUnboundedArrayQueue<>(4096));
    }

    public QueueFrame(long idHash,
            MessagePassingQueue<AbstractFrame> queue) {
        super(idHash, null);
        this.idHash = idHash;
        this.queue = queue;
    }

    public boolean enqueue(AbstractFrame frame) {
        if (queue.relaxedOffer(frame)) {
            sizeBytes.accumulateAndGet(frame.getSizeBytes(), QueueFrame::addCap);
            return true;
        }
        return false;
    }

    public void clear() {
        queue.clear();
    }

    public int drain(@NonNull DrainBuffer drainBuffer, int limit) {
        if (limit <= 0) {
            return 0;
        }

        drainBuffer.drainCount = 0;
        drainBuffer.drainedBytes = 0;
        int count = this.queue.drain(drainBuffer::accept, limit);

        if(count > 0) {
            sizeBytes.accumulateAndGet(-drainBuffer.drainedBytes, QueueFrame::addCap);
        }
        return count;
    }

    public int getQueueCount() {
        return queue.size();
    }

    @Override
    public long getSizeBytes() {
        return sizeBytes.get();
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

    private static long addCap(long num1, long num2) {
        long sum = num1 + num2;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}

