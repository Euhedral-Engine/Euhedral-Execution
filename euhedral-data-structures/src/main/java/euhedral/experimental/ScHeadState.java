package euhedral.experimental;

import euhedral.queues.common.QueueUtils;
import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.function.Consumer;

@SuppressWarnings({"unused"})
final class ScHeadState extends HeadState {

    private final int linkIndex;
    private final long mask;

    ScHeadState(Object[] queue, long mask) {
        super(queue);
        this.linkIndex = queue.length - 1;
        this.mask = mask;
    }

    public Object scPoll() {
        long head = getHeadPlain();
        int cIdx = QueueUtils.chunkIndex(head, this.mask);
        Object obj = this.scPeekInternal(cIdx);

        if (obj != null) {
            clearHeadQueueSlotPlain(cIdx);
            setHeadRelease(head + 1);
        }
        return obj;
    }

    public Object scPeek() {
        long head = getHeadPlain();
        int cIdx = QueueUtils.chunkIndex(head, this.mask);
        return scPeekInternal(cIdx);
    }

    public long scDrain(Consumer<Object> consumer, long limit) {
        Objects.requireNonNull(consumer);
        if (limit <= 0) {
            return 0;
        }

        final long headSnapshot = getHeadPlain();
        long head = headSnapshot;

        // Acquire before touching the queue.
        VarHandle.acquireFence();

        Object[] queue = getHeadQueuePlain();

        long diff = 0;
        while (diff < limit) {
            int cIdx = QueueUtils.chunkIndex(head, this.mask);
            Object obj = queue[cIdx];

            if (obj == null) {
                break;
            }

            if (obj == QueueUtils.SENTINEL) {
                Object[] temp = (Object[]) queue[this.linkIndex];
                if (temp == null) {
                    break;
                }
                queue[this.linkIndex] = null;
                queue = temp;
                setHeadQueuePlain(queue);
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
        Object[] queue = getHeadQueuePlain();

        Object obj = QueueUtils.loadAcquire(queue, cIdx);

        if (obj == null) {
            return null;
        }

        if (obj == QueueUtils.SENTINEL) {
            Object[] temp = (Object[]) QueueUtils.loadAcquire(queue, this.linkIndex);
            setHeadQueuePlain(temp);

            obj = QueueUtils.loadAcquire(temp, cIdx);
            QueueUtils.storeVolatile(queue, this.linkIndex, null);
        }

        return obj;
    }
}
