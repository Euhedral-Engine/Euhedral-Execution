package euhedral.experimental;

import euhedral.atomics.padding.HeadPad;
import euhedral.queues.common.QueueUtils;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

@SuppressWarnings({"unchecked", "unused"})
final class SpTailState<T> extends TailState {

    static final VarHandle TAIL;

    static {
        try {
            TAIL = MethodHandles.lookup().findVarHandle(PaddedHolder.class, "tail", long.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    final HeadState head;
    final State<T> state;
    private final long chunkMask;

    SpTailState(HeadState head, T[] queue, long chunkMask) {
        Objects.requireNonNull(head);
        Objects.requireNonNull(queue);
        this.head = head;
        this.state = new State<>(queue);
        this.chunkMask = chunkMask;
    }

    @Override
    long getTail() {
        return (long) TAIL.getAcquire(this.state);
    }

    void incrementTail(long tail) {
        TAIL.setRelease(this.state, QueueUtils.scaleAndAdd(tail, 1));
    }

    public boolean scOffer(T obj) {
        Objects.requireNonNull(obj);

        long tail = this.state.tail;
        long tailEpoch = this.state.tailEpoch;

        if ((tail & QueueUtils.UPPER_MASK) < tailEpoch) {
            this.state.queue[QueueUtils.chunkIndex(tail, this.chunkMask)] = obj;
            incrementTail(tail);
            return true;
        }

        slowStore(obj, tail);
        return true;
    }

    private void slowStore(T obj, long tail) {
        int cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);

        long head = this.head.getHeadAcquire();

        long diff = QueueUtils.unsignedDiff(head, tail);
        if (diff < this.chunkMask) {
            this.state.tailEpoch = QueueUtils.packEpoch(this.state.tailEpoch, this.chunkMask - diff);
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

        this.state.queue = (T[]) nextQueue;

        this.state.tailEpoch = QueueUtils.packEpoch(this.state.tailEpoch, this.chunkMask);
        incrementTail(tail);
    }

    static class PaddedHolder<T> extends HeadPad {

        long tail;
    }

    static class TailPad<T> extends PaddedHolder<T> {

        private byte b000, b001, b002, b003, b004, b005, b006, b007;
        private byte b008, b009, b010, b011, b012, b013, b014, b015;
        private byte b016, b017, b018, b019, b020, b021, b022, b023;
        private byte b024, b025, b026, b027, b028, b029, b030, b031;
        private byte b032, b033, b034, b035, b036, b037, b038, b039;
        private byte b040, b041, b042, b043, b044, b045, b046, b047;
        private byte b048, b049, b050, b051, b052, b053, b054, b055;
        private byte b056, b057, b058, b059, b060, b061, b062, b063;
        private byte b064, b065, b066, b067, b068, b069, b070, b071;
        private byte b072, b073, b074, b075, b076, b077, b078, b079;
        private byte b080, b081, b082, b083, b084, b085, b086, b087;
        private byte b088, b089, b090, b091, b092, b093, b094, b095;
        private byte b096, b097, b098, b099, b100, b101, b102, b103;
        private byte b104, b105, b106, b107, b108, b109, b110, b111;
        private byte b112, b113, b114, b115, b116, b117, b118, b119;
        private byte b120, b121, b122, b123, b124, b125, b126, b127;
    }

    static class State<T> extends TailPad<T> {

        long tailEpoch;
        T[] queue;

        State(T[] queue) {
            this.queue = queue;
        }
    }
}
