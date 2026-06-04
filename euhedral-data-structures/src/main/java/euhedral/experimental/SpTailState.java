package euhedral.experimental;

import euhedral.queues.common.QueueUtils;
import java.util.Objects;

@SuppressWarnings({"unused"})
final class SpTailState extends TailState {

    private final long chunkMask;

    SpTailState(HeadState head, Object[] queue, long chunkMask) {
        super(head, queue);
        this.chunkMask = chunkMask;
    }

    public void scOffer(Object obj) {
        Objects.requireNonNull(obj);

        long tail = getTailPlain();
        long tailEpoch = getTailEpochPlain();

        if (tail < tailEpoch) {
            storeInTailQueuePlain(QueueUtils.chunkIndex(tail, this.chunkMask), obj);
            setTailRelease(tail + 1);
            return;
        }

        slowStore(obj, tail);
    }

    private void slowStore(Object obj, long tail) {
        int cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);

        long head = getHead();

        long diff = QueueUtils.unsignedDiff(head, tail);
        if (diff < this.chunkMask) {
            addTailEpochPlain(this.chunkMask - diff);
            storeInTailQueuePlain(cIdx, obj);
            setTailRelease(tail + 1);
            return;
        }

        Object[] nextQueue = link(cIdx);
        setTailQueuePlain(nextQueue);

        addTailEpochPlain(this.chunkMask);

        nextQueue[cIdx] = obj;
        setTailRelease(tail + 1);
    }

    private Object[] link(int cIdx) {
        Object[] oldQueue = super.tailQueue;
        Object[] nextQueue = new Object[oldQueue.length];

        oldQueue[oldQueue.length - 1] = nextQueue;

        // Publish must happen after link.
        QueueUtils.storeVolatile(oldQueue, cIdx, QueueUtils.SENTINEL);
        return nextQueue;
    }
}
