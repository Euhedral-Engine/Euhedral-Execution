package euhedral.experimental;

import euhedral.atomics.padding.HeadPad;
import euhedral.queues.common.QueueUtils;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Objects;

@SuppressWarnings({"unused"})
final class MpTailState extends TailState {

    private static final VarHandle TAIL;
    private static final VarHandle EPOCH;
    private static final VarHandle QUEUE;

    static {
        try {
            TAIL = MethodHandles.lookup().findVarHandle(State.class, "tail", long.class);
            EPOCH = MethodHandles.lookup().findVarHandle(State.class, "epoch", long.class);
            QUEUE = MethodHandles.lookup().findVarHandle(State.class, "queue", Object[].class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private final HeadState head;
    private final State state;

    private final long chunkMask;

    private final int queueVersion;
    private final int linkIndex;

    MpTailState(HeadState head, Object[] queue, long chunkMask) {
        Objects.requireNonNull(head);
        Objects.requireNonNull(queue);
        if (queue.length < chunkMask + 3) {
            throw new IllegalArgumentException("queue.length < chunkMask + 3");
        }
        this.head = head;
        this.state = new State(queue);
        this.chunkMask = chunkMask;
        this.queueVersion = 0;
        this.linkIndex = queue.length - 1;
        queue[queueVersion] = 0;
    }

    void scOffer(Object obj) {
        Objects.requireNonNull(obj);

        Object[] queue = getQueue();
        long tail = getAndAddTail(QueueUtils.INCREMENT) & QueueUtils.UPPER_MASK;

        long rawEpoch = getRawEpoch();
        long epoch = rawEpoch & QueueUtils.UPPER_MASK;
        long version = getVersion(rawEpoch);

        while ((long) QueueUtils.loadAcquire(queue, queueVersion) != version) {
            queue = (Object[]) QueueUtils.loadAcquire(queue, linkIndex);
        }

        if (tail < epoch) {
            int cIdx = getChunkIndex(tail);
            QueueUtils.storeRelease(queue, cIdx, obj);
            return;
        }

        scSlowStore(obj, queue, tail, version, epoch, rawEpoch);
    }

    private void scSlowStore(Object obj, Object[] queue, long tail, long version, long epoch, long rawEpoch) {
        int cIdx = getChunkIndex(tail);

        long head = this.head.getHeadAcquire();

        int diff = (int) QueueUtils.unsignedDiff(head, tail);
        if (diff < this.chunkMask) {
            QueueUtils.storeRelease(queue, cIdx, obj);

            long nextEpoch = QueueUtils.packEpoch(version, epoch, this.chunkMask - diff);

            CAS_Epoch(version, rawEpoch, nextEpoch);
            return;
        }

        if(storeAndTryLink(queue, obj, cIdx, version)) {
            CAS_Epoch(version, rawEpoch, epoch);
        }
    }

    /// Tries to link the next chunk. Stores the value whether it succeeds or not.
    ///
    /// false - thread lost the link CAE and stored their value
    /// true - thread won the link CAE and stored their value
    private boolean storeAndTryLink(Object[] queue, Object obj, int cIdx, long version) {
        Object[] nextQueue = new Object[queue.length];
        nextQueue[this.queueVersion] = (int) (version + 1);

        Object[] observed = (Object[]) QueueUtils.compareAndExchange(queue, this.linkIndex, null,
                nextQueue);
        if (observed == null) {
            casQueue(queue, nextQueue);
            nextQueue[cIdx] = obj;

            // Publish must happen after link.
            QueueUtils.storeVolatile(queue, cIdx, QueueUtils.SENTINEL);
            return true;
        }

        QueueUtils.storeRelease(observed, cIdx, obj);
        return false;
    }

    @Override
    long getTail() {
        return (long) TAIL.getVolatile(this.state);
    }

    private long getAndAddTail(long val) {
        return (long) TAIL.getAndAddRelease(this.state, val);
    }

    private Object[] getQueue() {
        return (Object[]) QUEUE.getAcquire(this.state);
    }

    private boolean casQueue(Object[] curr, Object[] next) {
        return QUEUE.compareAndSet(this.state, curr, next);
    }

    private long getRawEpoch() {
        return (long) EPOCH.getAcquire(this.state);
    }

    private long getVersion(long rawEpoch) {
        return rawEpoch & QueueUtils.LOWER_MASK;
    }

    private int getChunkIndex(long tail) {
        long temp = QueueUtils.chunkIndex(tail >>> 32, this.chunkMask) + 1;
        return (int) temp;
    }

    private void CAS_Epoch(long currentVersion, long current, long next) {
        while (true) {
            long observed = (long) EPOCH.compareAndExchangeRelease(this.state, current, next);
            if (observed == current) {
                return;
            }
            if ((observed & QueueUtils.LOWER_MASK) != currentVersion) {
                return;
            }
            if(observed > next) {
                return;
            }
            current = observed;
        }
    }

    static class PaddedHolder extends HeadPad {

        long tail;
    }

    static class TailPad extends PaddedHolder {

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

    static class State extends TailPad {

        long epoch;
        Object[] queue;

        State(Object[] queue) {
            this.queue = queue;
        }
    }
}
