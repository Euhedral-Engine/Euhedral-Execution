package euhedral.experimental;

import euhedral.queues.common.QueueUtils;
import java.util.function.Consumer;

@SuppressWarnings({"unchecked", "unused"})
public class UnboundedMpscQueue<T> extends BaseConcurrentQueue {

    public UnboundedMpscQueue(int chunkSize) {
        super(new Object[(int) roundChunkSize(chunkSize) + 1], roundChunkSize(chunkSize) - 1);
    }

    public boolean offer(T obj) {
        mpOffer(obj);
        return true;
    }

    public T peek() {
        return (T) scPeek();
    }

    public T poll() {
        return (T) scPoll();
    }

    public void fill(T[] objs) {
        mpFill(objs);
    }

    public void fill(Iterable<T> objs) {
        mpFill((Iterable<Object>) objs);
    }

    public long drain(Consumer<T> consumer, long limit) {
        return scDrain((Consumer<Object>) consumer, limit);
    }

    public void clear() {
        while (scDrain(QueueUtils.NO_OP, Long.MAX_VALUE) > 0) {
            Thread.onSpinWait();
        }
    }
}
