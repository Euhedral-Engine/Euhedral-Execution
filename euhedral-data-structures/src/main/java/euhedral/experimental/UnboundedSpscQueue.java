package euhedral.experimental;

import euhedral.queues.common.QueueUtils;
import java.util.function.Consumer;
import lombok.Getter;

@SuppressWarnings({"unchecked", "unused"})
public class UnboundedSpscQueue<T> {

    private static final Object SENTINEL = new Object();

    private final ScHeadState<T> headState;
    private final SpTailState<T> tailState;

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
        this.headState = new ScHeadState<>(queue, chunkMask);
        this.tailState = new SpTailState<>(this.headState, queue, chunkMask);
    }

    public boolean offer(T obj) {
        return this.tailState.scOffer(obj);
    }

    public T peek() {
        return this.headState.scPeek();
    }

    public T poll() {
        return this.headState.scPoll();
    }

    public long drain(Consumer<T> consumer, long limit) {
        return this.headState.scDrain(consumer, limit);
    }

    public void clear() {
        while (drain((Consumer<T>) QueueUtils.NO_OP, Long.MAX_VALUE) > 0) {
            Thread.onSpinWait();
        }
    }
}
