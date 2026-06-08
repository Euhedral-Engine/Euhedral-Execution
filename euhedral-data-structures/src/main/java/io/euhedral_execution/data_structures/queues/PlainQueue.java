package io.euhedral_execution.data_structures.queues;

import io.euhedral_execution.data_structures.queues.common.BatchableQueue;
import io.euhedral_execution.data_structures.queues.common.QueueUtils;
import java.util.AbstractQueue;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.NonNull;

@SuppressWarnings({"unchecked", "unused"})
public class PlainQueue<T> extends AbstractQueue<T> implements BatchableQueue<T> {

    private final QueueHolder queue;
    private final long chunkMask;
    private final boolean bounded;
    private final long capacity;

    private final QueueIter iterator =  new QueueIter();

    public PlainQueue(int chunkSize) {
        this(chunkSize, false);
    }

    PlainQueue(int chunkSize, boolean bounded) {
        this.queue = new QueueHolder(new Object[QueueUtils.queueSize(chunkSize)]);
        this.chunkMask = QueueUtils.chunkMask(chunkSize);
        this.bounded = bounded;
        if(bounded) {
            this.capacity = (QueueUtils.chunkMask(chunkSize) >>> QueueUtils.SHIFT);
        } else {
            this.capacity = Long.MAX_VALUE;
        }
    }

    @Override
    public final int fill(T[] objs) {
        return fill(objs, 0, objs.length);
    }

    @Override
    public final int fill(T[] objs, int start, int end) {
        Objects.requireNonNull(objs);
        for (int i = start; i < end; i++) {
            if (!offer(objs[i])) {
                return i - start;
            }
        }
        return end - start;
    }

    @Override
    public final int fill(Collection<T> objs) {
        Objects.requireNonNull(objs);
        int total = 0;
        for (T obj : objs) {
            if (!offer(obj)) {
                return total;
            }
            total++;
        }
        return total;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public boolean contains(Object o) {
        return false;
    }

    @Override
    public @NonNull Iterator<T> iterator() {
        this.iterator.pos = this.queue.head;
        this.iterator.queue = this.queue.queue;
        return this.iterator;
    }

    @Override
    public final boolean offer(T obj) {
        Objects.requireNonNull(obj);

        long tail = this.queue.tail;
        long head = this.queue.head;

        Object[] queue = this.queue.queue;
        int cIdx = QueueUtils.chunkIndex(tail, this.chunkMask);
        if (tail == head - 1) {
            if (this.bounded) {
                return false;
            }
            Object[] next = new Object[queue.length];
            queue[queue.length - 1] = next;
            queue[cIdx] = QueueUtils.SENTINEL;
            queue = next;
        }
        queue[cIdx] = obj;
        this.queue.tail += QueueUtils.INCREMENT;
        return true;
    }

    @Override
    public final long drain(Consumer<T> consumer, long limit) {
        long total = 0;
        for (long i = this.queue.head; i < this.queue.tail && total < limit;
                i += QueueUtils.INCREMENT) {
            T obj = poll();
            if (obj == null) {
                break;
            }
            consumer.accept(obj);
            total++;
        }
        return total;
    }

    @Override
    public final T poll() {
        int cIdx = QueueUtils.chunkIndex(this.queue.head, this.chunkMask);

        T obj = peekInternal(cIdx);
        if (obj == null) {
            return null;
        }
        this.queue.queue[cIdx] = null;
        this.queue.head += QueueUtils.INCREMENT;
        return obj;
    }

    @Override
    public final T peek() {
        int cIdx = QueueUtils.chunkIndex(this.queue.head, this.chunkMask);
        return peekInternal(cIdx);
    }

    private T peekInternal(int cIdx) {
        Object[] queue = this.queue.queue;

        Object obj = queue[cIdx];
        if (obj == QueueUtils.SENTINEL) {
            queue = (Object[]) queue[queue.length - 1];
            this.queue.queue = queue;
        }
        return (T) queue[cIdx];
    }

    @Override
    public final long capacity() {
        return this.capacity;
    }

    @Override
    public void clear() {
        drain((Consumer<T>) QueueUtils.NO_OP, Long.MAX_VALUE);
    }

    @Override
    public long sizeLong() {
        return (this.queue.tail - this.queue.head) >>> QueueUtils.SHIFT;
    }

    private class QueueIter implements Iterator<T> {
        long pos;
        Object[] queue;

        @Override
        public boolean hasNext() {
            int cIdx = QueueUtils.chunkIndex(pos, chunkMask);
            if(queue[cIdx] == QueueUtils.SENTINEL) {
                queue = (Object[]) queue[queue.length - 1];
            }
            return queue[cIdx] != null;
        }

        @Override
        public T next() {
            int cIdx = QueueUtils.chunkIndex(pos, chunkMask);
            if(queue[cIdx] == QueueUtils.SENTINEL) {
                queue = (Object[]) queue[queue.length - 1];
            }
            pos += QueueUtils.INCREMENT;
            return (T) queue[cIdx];
        }
    }

    @SuppressWarnings("unused")
    private static class TopPad {

        private long p00, p01, p02, p03, p04, p05, p06, p07;
        private long p08, p09, p10, p11, p12, p13, p14, p15;
    }

    private static class ValueHolder extends TopPad {

        long head;
        long tail;
        Object[] queue;

        ValueHolder(Object[] queue) {
            this.queue = queue;
        }
    }

    @SuppressWarnings("unused")
    private static class QueueHolder extends ValueHolder {

        private long p00, p01, p02, p03, p04, p05, p06, p07;
        private long p08, p09, p10, p11, p12, p13, p14, p15;

        QueueHolder(Object[] queue) {
            super(queue);
        }
    }
}
