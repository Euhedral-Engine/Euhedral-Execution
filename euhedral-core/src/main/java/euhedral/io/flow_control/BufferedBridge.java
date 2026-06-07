package euhedral.io.flow_control;

import euhedral.io.frames.AbstractFrame;
import io.euhedral_execution.data_structures.queues.common.PartitionedQueue;
import java.util.function.Consumer;

/// An intermediary buffer used for communication between pipeline stages.
@SuppressWarnings("unused")
public class BufferedBridge {

    private final PartitionedQueue<AbstractFrame> buffer;
    private final Consumer<AbstractFrame> drainFunc;
    private final Consumer<AbstractFrame> hookOnOffer;

    public BufferedBridge(PartitionedQueue<AbstractFrame> buffer,
            Consumer<AbstractFrame> drainFunc) {
        this(buffer, drainFunc, null);
    }

    public BufferedBridge(PartitionedQueue<AbstractFrame> buffer,
            Consumer<AbstractFrame> drainFunc, Consumer<AbstractFrame> hookOnOffer) {
        this.buffer = buffer;
        this.drainFunc = drainFunc;
        this.hookOnOffer = hookOnOffer;
    }

    public long drain() {
        return this.buffer.drain(this.drainFunc, Integer.MAX_VALUE);
    }

    public boolean offer(AbstractFrame frame) {
        boolean success = this.buffer.offer(frame);
        if(success && this.hookOnOffer != null) {
            this.hookOnOffer.accept(frame);
        }
        return success;
    }
}
