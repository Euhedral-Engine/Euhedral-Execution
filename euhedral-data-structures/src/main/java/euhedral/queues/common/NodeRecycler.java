package euhedral.queues.common;

import euhedral.queues.common.QueueNode.Type;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class NodeRecycler<T> {

    private final AtomicReference<QueueNode<T>> stack = new AtomicReference<>(null);
    private final AtomicInteger count = new AtomicInteger(0);
    private final QueueNode.Type type;
    private final int capacity;

    public NodeRecycler(QueueNode.Type type, int capacity) {
        this.type = type;
        this.capacity = capacity;
    }

    public QueueNode<T> pop() {
        if (type == QueueNode.Type.PLAIN) {
            return popPlain();
        }

        while (true) {
            QueueNode<T> head = stack.get();
            if (head == null) {
                return null;
            }

            QueueNode<T> next = head.next.getAcquire();

            if (stack.compareAndSet(head, next)) {
                head.reset();
                count.decrementAndGet();
                return head;
            }
        }
    }

    public QueueNode<T> popPlain() {
        QueueNode<T> head = stack.getPlain();
        if (head == null) {
            return null;
        }

        QueueNode<T> next = head.next.getPlain();
        stack.setPlain(next);
        count.setPlain(count.getPlain() - 1);

        head.reset();
        return head;
    }

    public void recycle(QueueNode<T> node) {
        if (type == Type.PLAIN) {
            recyclePlain(node);
            return;
        }

        if (!casIncrement()) {
            return;
        }

        QueueNode<T> head = stack.getAcquire();
        while (true) {
            node.next.setRelease(head);

            QueueNode<T> witness = stack.compareAndExchange(head, node);
            if (head == witness) {
                return;
            }
            head = witness;
        }
    }

    private void recyclePlain(QueueNode<T> node) {
        if (this.count.getPlain() == capacity) {
            return;
        }
        this.count.setPlain(this.count.getPlain() + 1);
        QueueNode<T> head = stack.getPlain();
        node.next.setPlain(head);
        stack.setPlain(node);
    }

    private boolean casIncrement() {
        int count;
        do {
            count = this.count.getAcquire();
            if (count == capacity) {
                return false;
            }
        } while (!this.count.compareAndSet(count, count + 1));
        return true;
    }
}
