package euhedral.io.utils;

import euhedral.io.frames.AbstractFrame;
import org.jctools.queues.MessagePassingQueue;

public class DrainBuffer implements MessagePassingQueue.Consumer<AbstractFrame> {

    public final MessagePassingQueue<AbstractFrame> buffer;
    private final boolean threadSafe;

    public final FlowRecorder arrivalLatencyRecorder = new FlowRecorder();

    public long xor1 = 0;
    public long xor2 = 0;
    public int uniqueOrdered = 0;
    public long drainCount = 0;
    public long drainedBytes = 0;

    public DrainBuffer(MessagePassingQueue<AbstractFrame> buffer, boolean threadSafe) {
        this.buffer = buffer;
        this.threadSafe = threadSafe;
    }

    public void reset() {
        xor1 = 0;
        xor2 = 0;
        uniqueOrdered = 0;
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
        if(frame.isOrdered()) {
            long hash = (xor1 ^ frame.getCombinedHash()) ^ xor2;
            if(hash != frame.getCombinedHash()) {
                xor1 ^= frame.getCombinedHash();
                xor2 ^= frame.getCombinedHash();
                uniqueOrdered++;
            }
        }
        drainCount++;
        drainedBytes += frame.getSizeBytes();
    }
}
