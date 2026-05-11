package euhedral.queues;

import euhedral.queues.common.QueueNode;
import euhedral.queues.common.QueueNode.Type;
import java.util.concurrent.atomic.AtomicBoolean;

/// An unbounded multi-producer-single-consumer array queue with partitions. This class is
/// thread-safe for any offer method. It is not thread-safe for peek, poll, or drain. It is derived
/// from [PartitionedUnboundedArrayQueue] but overrides the logic for head and tail interaction to
/// make it safe for use as an MPSC.
///
/// @param <T> Type to store
public class PartitionedUnboundedMpscArrayQueue<T> extends PartitionedUnboundedArrayQueue<T> {

    protected final AtomicBoolean movingTail = new AtomicBoolean(false);

    public PartitionedUnboundedMpscArrayQueue(int partitions, int chunkSize, int maxPooledChunks) {
        super(partitions, chunkSize, maxPooledChunks, Type.MPSC);
    }

    @Override
    protected final boolean acquireTailMovePermission() {
        return this.movingTail.compareAndSet(false, true);
    }

    @Override
    protected void releaseTailMovePermission() {
        this.movingTail.setRelease(false);
    }

    @Override
    protected final QueueNode<T> getNextHeadNode(QueueNode<T> head) {
        return head.next.getAcquire();
    }

    @Override
    protected final QueueNode<T> getTailNode() {
        return super.tail.getAcquire();
    }

    @Override
    protected final void setTailNode(QueueNode<T> tail) {
        super.tail.setRelease(tail);
    }

    @Override
    protected final void setNextTailNode(QueueNode<T> tail, QueueNode<T> next) {
        tail.next.setRelease(next);
    }

    @Override
    protected void moveHeadsForward(QueueNode<T> commonHead, QueueNode<T> nextHead) {
        for (int i = 0; i < super.partitions; i++) {
            if (super.heads[i] == commonHead) {
                super.heads[i] = nextHead;
                commonHead.refs[i] = false;
            } else if (commonHead.refs[i]) {
                return;
            }
        }

        if (super.recycler != null) {
            commonHead.reclaimed.setRelease(true);
            super.recycler.recycle(commonHead);
        }
    }
}
