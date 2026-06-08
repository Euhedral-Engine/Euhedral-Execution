package euhedral.io.utils;

import euhedral.io.frames.AbstractFrame;
import io.euhedral_execution.data_structures.queues.common.BatchableQueue;
import java.util.function.Consumer;
import lombok.Getter;

/// Used for draining from queues. Automatically tracks the number of frames and bytes drained as
/// well as their arrival latency.
public class DrainBuffer implements Consumer<AbstractFrame> {

    public final BatchableQueue<AbstractFrame> buffer;
    public final FlowRecorder arrivalLatencyRecorder = new FlowRecorder();
    private final boolean threadSafe;
    @Getter
    private final int size;
    public long drainCount = 0;
    public long drainedBytes = 0;

    public DrainBuffer(BatchableQueue<AbstractFrame> buffer, int size, boolean threadSafe) {
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
        while (!buffer.offer(frame)) {
            Thread.onSpinWait();
        }
        record(frame);
    }

    public void record(AbstractFrame frame) {
        if (frame.getIngestNs() > 0) {
            long now = System.nanoTime();
            arrivalLatencyRecorder.record(now, now - frame.getIngestNs(), threadSafe);
        }
        drainCount++;
        drainedBytes += frame.getSizeBytes();
    }
}
