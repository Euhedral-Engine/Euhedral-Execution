package euhedral.io.flow_control;

import euhedral.io.frames.AbstractFrame;
import euhedral.queues.QueueConsumer;
import euhedral.queues.common.PartitionedQueue;

/// An intermediary buffer used for communication between pipeline stages.
@SuppressWarnings("unused")
public class BufferedBridge {

    private final PartitionedQueue<AbstractFrame> buffer;
    private final QueueConsumer<AbstractFrame> drainFunc;
    private final QueueConsumer<AbstractFrame> hookOnOffer;

    public BufferedBridge(PartitionedQueue<AbstractFrame> buffer,
            QueueConsumer<AbstractFrame> drainFunc) {
        this(buffer, drainFunc, null);
    }

    public BufferedBridge(PartitionedQueue<AbstractFrame> buffer,
            QueueConsumer<AbstractFrame> drainFunc, QueueConsumer<AbstractFrame> hookOnOffer) {
        this.buffer = buffer;
        this.drainFunc = drainFunc;
        this.hookOnOffer = hookOnOffer;
    }

    public int drain() {
        return this.buffer.drain(this.drainFunc, Integer.MAX_VALUE);
    }

    public boolean offer(AbstractFrame frame) {
        boolean success = this.buffer.offer(frame);
        if(success && this.hookOnOffer != null) {
            this.hookOnOffer.consume(frame);
        }
        return success;
    }
}
