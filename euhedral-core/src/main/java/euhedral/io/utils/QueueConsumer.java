package euhedral.io.utils;

import euhedral.io.frames.AbstractFrame;
import java.util.function.Consumer;

/// Used for draining from queues. Automatically tracks the number of frames and bytes drained as
/// well as their arrival latency.
public class QueueConsumer implements Consumer<AbstractFrame> {

    public final Consumer<AbstractFrame> consumer;

    public long drainCount = 0;

    public QueueConsumer(Consumer<AbstractFrame> consumer) {
        this.consumer = consumer;
    }

    public void reset() {
        this.drainCount = 0;
    }

    @Override
    public void accept(AbstractFrame frame) {
        this.drainCount++;
        this.consumer.accept(frame);
    }
}
