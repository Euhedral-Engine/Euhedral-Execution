package euhedral.io.utils;

import euhedral.io.frames.AbstractFrame;
import lombok.Getter;
import org.jctools.queues.MessagePassingQueue;

public class DrainBuffer implements MessagePassingQueue.Consumer<AbstractFrame> {

    public final MessagePassingQueue<AbstractFrame> buffer;
    private final boolean threadSafe;
    @Getter
    private final int size;

    public final FlowRecorder arrivalLatencyRecorder = new FlowRecorder();

    public long drainCount = 0;
    public long drainedBytes = 0;

    public DrainBuffer(MessagePassingQueue<AbstractFrame> buffer, int size, boolean threadSafe) {
        this.buffer = buffer;
        this.threadSafe = threadSafe;
        this.size = size;
    }

    public void reset() {
        drainCount = 0;
        drainedBytes = 0;
    }

    @Override
    public void accept(AbstractFrame frame) {
        while (!buffer.relaxedOffer(frame)) {
            Thread.onSpinWait();
        }
        if(frame.getIngestNs() > 0) {
            long now = System.nanoTime();
            arrivalLatencyRecorder.record(now, now - frame.getIngestNs(), threadSafe);
        }
        drainCount++;
        drainedBytes += frame.getSizeBytes();
    }
}
