package euhedral.queues;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MpmcNodeRecycler<T> {
    private final AtomicReference<QueueNode<T>> stack = new AtomicReference<>(null);
    private final AtomicInteger count = new AtomicInteger(0);
    private final int capacity;

    public MpmcNodeRecycler(int capacity) {
        this.capacity = capacity;
    }

    public QueueNode<T> pop() {
        while (true) {
            QueueNode<T> head = stack.get();
            if (head == null) {
                return null;
            }

            QueueNode<T> next = head.next;

            if (stack.compareAndSet(head, next)) {
                head.reset();
                count.decrementAndGet();
                return head;
            }
        }
    }

    public void recycle(QueueNode<T> node) {
        int count;
        do {
            count = this.count.get();
            if(count + 1 > capacity) {
                return;
            }
        } while(!this.count.compareAndSet(count, count + 1));

        QueueNode<T> head = stack.get();
        while (true) {
            node.next = head;

            QueueNode<T> witness = stack.compareAndExchange(head, node);
            if(head == witness) {
                return;
            }
            head = witness;
        }
    }
}
