package euhedral.io.flow_control;

import euhedral.io.frames.AbstractFrame;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MessagePassingQueue.Consumer;

@SuppressWarnings("unused")
public class LockFreeSink {

    private final MessagePassingQueue<AbstractFrame> buffer;
    private final Consumer<AbstractFrame> drainFunc;
    private final Consumer<AbstractFrame> hookOnOffer;

    public LockFreeSink(MessagePassingQueue<AbstractFrame> buffer,
            Consumer<AbstractFrame> drainFunc) {
        this(buffer, drainFunc, null);
    }

    public LockFreeSink(MessagePassingQueue<AbstractFrame> buffer,
            Consumer<AbstractFrame> drainFunc, Consumer<AbstractFrame> hookOnOffer) {
        this.buffer = buffer;
        this.drainFunc = drainFunc;
        this.hookOnOffer = hookOnOffer;
    }

    public int drain() {
        return this.buffer.drain(this.drainFunc);
    }

    public boolean relaxedOffer(AbstractFrame frame) {
        boolean success = this.buffer.relaxedOffer(frame);
        if(success && this.hookOnOffer != null) {
            this.hookOnOffer.accept(frame);
        }
        return success;
    }

    public boolean offer(AbstractFrame frame) {
        boolean success = this.buffer.offer(frame);
        if(success && this.hookOnOffer != null) {
            this.hookOnOffer.accept(frame);
        }
        return success;
    }
}
