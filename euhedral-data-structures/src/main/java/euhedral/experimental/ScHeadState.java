package euhedral.experimental;

import euhedral.queues.common.QueueUtils;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.function.Consumer;

@SuppressWarnings({"unused"})
final class ScHeadState extends HeadState {

    private final State state;
    private final int chunkSize;
    private final int mask;

    ScHeadState(Object[] queue, int mask) {
        this.state = new State(queue);
        this.chunkSize = mask + 1;
        this.mask = mask;
    }

    @Override
    long getHeadAcquire() {
        return (long) HEAD.getAcquire(this.state);
    }

    private void setHeadRelease(long head) {
        HEAD.setRelease(this.state, head);
    }

    public Object scPoll() {
        long head = this.state.head;
        int cIdx = QueueUtils.chunkIndex(head, this.mask);
        Object obj = this.scPeekInternal(cIdx);

        if (obj != null) {
            QueueUtils.storeRelease(state.queue, cIdx, null);
            setHeadRelease(head + QueueUtils.INCREMENT);
        }
        return obj;
    }

    public Object scPeek() {
        long head = this.state.head;
        int cIdx = QueueUtils.chunkIndex(head, this.mask);
        return scPeekInternal(cIdx);
    }

    public long scDrain(Consumer<Object> consumer, long limit) {
        Objects.requireNonNull(consumer);
        if (limit <= 0) {
            return 0;
        }

        final long headSnapshot = this.state.head;
        long head = headSnapshot;

        // Acquire before touching the queue.
        VarHandle.acquireFence();

        Object[] queue = this.state.queue;

        long diff = 0;
        while (diff < limit && diff < QueueUtils.LOWER_MASK) {
            int cIdx = QueueUtils.chunkIndex(head, this.mask);
            Object obj = queue[cIdx];

            if (obj == null) {
                break;
            }

            if (obj == QueueUtils.SENTINEL) {
                Object[] temp = (Object[]) queue[this.chunkSize];
                if (temp == null) {
                    break;
                }
                queue[this.chunkSize] = null;
                queue = temp;
                this.state.queue = queue;
                continue;
            }

            consumer.accept(obj);
            queue[cIdx] = null;
            head++;
            diff = head - headSnapshot;
        }

        long total = head - headSnapshot;
        VarHandle.releaseFence();

        setHeadRelease(head);

        return total;
    }

    private Object scPeekInternal(int cIdx) {
        Object[] queue = this.state.queue;

        Object obj = QueueUtils.loadAcquire(this.state.queue, cIdx);

        if (obj == null) {
            return null;
        }

        if (obj == QueueUtils.SENTINEL) {
            Object[] temp = (Object[]) QueueUtils.loadAcquire(queue, this.chunkSize);
            this.state.queue = temp;

            obj = QueueUtils.loadAcquire(temp, cIdx);
            QueueUtils.storeVolatile(queue, this.chunkSize, null);
        }

        return obj;
    }
}
