package euhedral.io.utils;

import euhedral.io.frames.AbstractFrame;
import org.jctools.queues.MessagePassingQueue;

public class DrainBuffer implements MessagePassingQueue.Consumer<AbstractFrame> {

    public final MessagePassingQueue<AbstractFrame> buffer;
    private final boolean threadSafe;

    public final FlowRecorder arrivalLatencyRecorder = new FlowRecorder();

    public long drainCount = 0;
    public long drainedBytes = 0;

    public DrainBuffer(MessagePassingQueue<AbstractFrame> buffer, boolean threadSafe) {
        this.buffer = buffer;
        this.threadSafe = threadSafe;
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
