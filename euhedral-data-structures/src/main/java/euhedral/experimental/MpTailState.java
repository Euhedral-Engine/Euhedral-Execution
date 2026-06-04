package euhedral.experimental;

import euhedral.queues.common.QueueUtils;
import java.util.Objects;

@SuppressWarnings({"unused"})
final class MpTailState extends TailState {

    private final long slots;
    private final long chunkMask;

    private final int linkIndex;

    MpTailState(HeadState head, Object[] queue, long chunkMask) {
        super(head, queue);
        Objects.requireNonNull(head);
        this.slots = chunkMask;
        this.chunkMask = chunkMask << 1;
        this.linkIndex = queue.length - 1;
    }

    void offer(Object obj) {
        Objects.requireNonNull(obj);

        int cIdx;
        Object[] queue;
        while(true) {
            long tail = getTailAcquire();

            if((tail & 1) == 1) {
                continue;
            }

            queue = getTailQueueAcquire();
            long epoch = getTailEpochAcquire();
            // Fastest
            if((epoch - tail) > 0) {
                if(!casTail(tail, tail + 2)) {
                    continue;
                }
                cIdx = QueueUtils.chunkIndex(tail, this.chunkMask) >>> 1;
                break;
            }
            // Slower
            if(updateEpoch(tail, epoch)) {
                continue;
            }

            // Slowest
            if (casTail(tail, tail + 1)) {
                cIdx = QueueUtils.chunkIndex(tail, this.chunkMask) >>> 1;
                queue = linkChunk(queue, cIdx);
                setTailQueuePlain(queue);
                setTailEpochPlain(tail + this.chunkMask);
                getAndAddTail(1);
                break;
            }
        }
        QueueUtils.storeRelease(queue, cIdx, obj);
    }

    private boolean updateEpoch(long tail, long epoch) {
        long head = getHead();
        tail >>>= 1;

        long distance = tail - head;
        long diff = this.slots - distance;
        if(diff > 0) {
            long update = diff << 1;
            casTailEpoch(epoch, epoch + update);
            return true;
        }
        return false;
    }

    private Object[] linkChunk(Object[] oldChunk, int cIdx) {
        Object[] nextChunk = new Object[oldChunk.length];
        oldChunk[this.linkIndex] = nextChunk;

        // Publish after linking
        QueueUtils.storeVolatile(oldChunk, cIdx, QueueUtils.SENTINEL);
        return nextChunk;
    }
}
