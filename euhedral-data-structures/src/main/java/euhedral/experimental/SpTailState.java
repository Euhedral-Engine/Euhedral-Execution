package euhedral.experimental;

import euhedral.atomics.padding.HeadPad;
import euhedral.queues.common.QueueUtils;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

@SuppressWarnings({"unused"})
final class SpTailState extends TailState {

    static final VarHandle TAIL;

    static {
        try {
            TAIL = MethodHandles.lookup().findVarHandle(PaddedHolder.class, "tail", long.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    final HeadState head;
    final State state;
    private final long chunkMask;

    SpTailState(HeadState head, Object[] queue, long chunkMask) {
        Objects.requireNonNull(head);
        Objects.requireNonNull(queue);
        this.head = head;
        this.state = new State(queue);
        this.chunkMask = chunkMask;
    }

    @Override
    long getTail() {
        return (long) TAIL.getAcquire(this.state);
    }

    void incrementTail(long tail) {
        TAIL.setRelease(this.state, tail + 1);
    }

    public boolean scOffer(Object obj) {
        Objects.requireNonNull(obj);

        long tail = this.state.tail;
        long tailEpoch = this.state.tailEpoch;

        if (tail < tailEpoch) {
            this.state.queue[QueueUtils.chunkIndex(tail, this.chunkMask)] = obj;
            incrementTail(tail);
            return true;
        }

        slowStore(obj, tail);
        return true;
    }

    private void slowStore(Object obj, long tail) {
        int cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);

        long head = this.head.getHeadAcquire();

        long diff = QueueUtils.unsignedDiff(head, tail);
        if (diff < this.chunkMask) {
            this.state.tailEpoch += this.chunkMask - diff;
            this.state.queue[cIdx] = obj;
            incrementTail(tail);
            return;
        }

        Object[] oldQueue = this.state.queue;
        Object[] nextQueue = new Object[oldQueue.length];

        nextQueue[cIdx] = obj;
        oldQueue[oldQueue.length - 1] = nextQueue;

        // Publish must happen after link.
        QueueUtils.storeVolatile(oldQueue, cIdx, QueueUtils.SENTINEL);

        this.state.queue = nextQueue;

        this.state.tailEpoch += this.chunkMask;
        incrementTail(tail);
    }

    static class State extends TailPad {

        long tailEpoch;
        Object[] queue;

        State(Object[] queue) {
            this.queue = queue;
        }
    }
}
