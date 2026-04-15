package euhedral.io.flow_control;

import euhedral.io.frames.AbstractFrame;
import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MessagePassingQueue.Consumer;

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
        return buffer.drain(drainFunc);
    }

    public boolean relaxedOffer(AbstractFrame frame) {
        boolean success = buffer.relaxedOffer(frame);
        if(success && hookOnOffer != null) {
            hookOnOffer.accept(frame);
        }
        return success;
    }

    public boolean offer(AbstractFrame frame) {
        boolean success = buffer.offer(frame);
        if(success && hookOnOffer != null) {
            hookOnOffer.accept(frame);
        }
        return success;
    }
}
