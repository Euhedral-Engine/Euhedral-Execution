package euhedral.io.utils;

import java.util.function.Consumer;

import euhedral.io.frames.AbstractFrame;
import euhedral.queues.common.PartitionedQueue;
import lombok.Getter;

/// Used for draining from queues. Automatically tracks the number of frames and bytes drained as
/// well as their arrival latency.
public class DrainBuffer implements Consumer<AbstractFrame> {

    public final PartitionedQueue<AbstractFrame> buffer;
    private final boolean threadSafe;
    @Getter
    private final int size;

    public final FlowRecorder arrivalLatencyRecorder = new FlowRecorder();

    public long drainCount = 0;
    public long drainedBytes = 0;

    public DrainBuffer(PartitionedQueue<AbstractFrame> buffer, int size, boolean threadSafe) {
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
        while (!buffer.offer(0, frame)) {
            Thread.onSpinWait();
        }
        if (frame.getIngestNs() > 0) {
            long now = System.nanoTime();
            arrivalLatencyRecorder.record(now, now - frame.getIngestNs(), threadSafe);
        }
        drainCount++;
        drainedBytes += frame.getSizeBytes();
    }
}
