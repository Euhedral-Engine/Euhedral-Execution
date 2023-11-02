package euhedral.queues.common;

import euhedral.queues.QueueNode;
import euhedral.queues.QueueNode.Type;
import lombok.Getter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class NodeRecycler<T> {

    private final AtomicReference<QueueNode<T>> stack = new AtomicReference<>(null);
    private final AtomicInteger count = new AtomicInteger(0);
    private final QueueNode.Type type;
    @Getter
    private final int capacity;

    public NodeRecycler(QueueNode.Type type, int capacity) {
        this.type = type;
        this.capacity = capacity;
    }

    public int getCount() {
        if(this.type == Type.PLAIN) {
            return this.count.getPlain();
        }
        return this.count.getAcquire();
    }

    public QueueNode<T> pop() {
        if (this.type == Type.PLAIN) {
            return popPlain();
        }

        while (true) {
            QueueNode<T> head = this.stack.get();
            if (head == null) {
                return null;
            }

            QueueNode<T> next = head.next.getAcquire();

            if (this.stack.compareAndSet(head, next)) {
                head.clear();
                this.count.decrementAndGet();
                return head;
            }
        }
    }

    public QueueNode<T> popPlain() {
        QueueNode<T> head = this.stack.getPlain();
        if (head == null) {
            return null;
        }

        QueueNode<T> next = head.next.getPlain();
        this.stack.setPlain(next);
        this.count.setPlain(this.count.getPlain() - 1);

        head.clear();
        return head;
    }

    public void recycle(QueueNode<T> node) {
        if(node == null) {
            return;
        }
        if (this.type == Type.PLAIN) {
            recyclePlain(node);
            return;
        }

        if (!casIncrement()) {
            return;
        }

        QueueNode<T> head = this.stack.getAcquire();
        while (true) {
            node.next.setRelease(head);

            QueueNode<T> witness = this.stack.compareAndExchange(head, node);
            if (head == witness) {
                return;
            }
            head = witness;
        }
    }

    private void recyclePlain(QueueNode<T> node) {
        if (this.count.getPlain() == this.capacity) {
            return;
        }
        this.count.setPlain(this.count.getPlain() + 1);
        QueueNode<T> head = stack.getPlain();
        node.next.setPlain(head);
        this.stack.setPlain(node);
    }

    private boolean casIncrement() {
        int count;
        do {
            count = this.count.getAcquire();
            if (count == this.capacity) {
                return false;
            }
        } while (!this.count.compareAndSet(count, count + 1));
        return true;
    }
}
