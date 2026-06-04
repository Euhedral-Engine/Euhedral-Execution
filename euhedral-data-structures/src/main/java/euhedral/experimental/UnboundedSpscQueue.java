package euhedral.experimental;

import euhedral.queues.common.QueueUtils;
import java.util.function.Consumer;
import lombok.Getter;

@SuppressWarnings({"unchecked", "unused"})
public class UnboundedSpscQueue<T> {

    private static final Object SENTINEL = new Object();

    private final ScHeadState headState;
    private final SpTailState tailState;

    @Getter
    private final int chunkSize;

    public UnboundedSpscQueue(int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }

        chunkSize = Integer.highestOneBit((chunkSize - 1) << 1);
        this.chunkSize = chunkSize;

        T[] queue = (T[]) new Object[chunkSize + 1];

        int chunkMask = chunkSize - 1;
        this.headState = new ScHeadState(queue, chunkMask);
        this.tailState = new SpTailState(this.headState, queue, chunkMask);
    }

    public boolean offer(T obj) {
        this.tailState.scOffer(obj);
        return true;
    }

    public T peek() {
        return (T) this.headState.scPeek();
    }

    public T poll() {
        return (T) this.headState.scPoll();
    }

    public long drain(Consumer<T> consumer, long limit) {
        return this.headState.scDrain((Consumer<Object>) consumer, limit);
    }

    public void clear() {
        while (this.headState.scDrain(QueueUtils.NO_OP, Long.MAX_VALUE) > 0) {
            Thread.onSpinWait();
        }
    }
}
