package euhedral.experimental;

import euhedral.queues.common.QueueUtils;
import java.util.function.Consumer;

@SuppressWarnings({"unchecked", "unused"})
public final class UnboundedSpscQueue<T> extends BaseConcurrentQueue {

    public UnboundedSpscQueue(int chunkSize) {
        super(chunkSize);
    }

    public boolean offer(T obj) {
        spOffer(obj);
        return true;
    }

    public T peek() {
        return (T) scPeek();
    }

    public T poll() {
        return (T) scPoll();
    }

    public void fill(T[] objs) {
        spFill(objs);
    }

    public void fill(Iterable<T> objs) {
        spFill((Iterable<Object>) objs);
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
