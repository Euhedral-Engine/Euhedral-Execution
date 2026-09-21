package io.euhedral_execution.core.config;

import java.util.BitSet;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/// Publishes the live worker identities belonging to one clone, including its optional SMT buddy.
public final class CloneLivenessRegistry {

    private final int[] cpus;
    private final WorkerSlot[] slots;

    public CloneLivenessRegistry(BitSet effectiveCpus) {
        Objects.requireNonNull(effectiveCpus);
        this.cpus = new int[effectiveCpus.cardinality()];
        this.slots = new WorkerSlot[this.cpus.length];
        int index = 0;
        for (int cpu = effectiveCpus.nextSetBit(0); cpu >= 0; cpu = effectiveCpus.nextSetBit(cpu + 1)) {
            this.cpus[index] = cpu;
            this.slots[index] = new WorkerSlot();
            index++;
        }
    }

    /// Returns the mutable publication slot for one participating logical CPU.
    public WorkerSlot slot(int cpu) {
        for (int i = 0; i < this.cpus.length; i++) {
            if (this.cpus[i] == cpu) {
                return this.slots[i];
            }
        }
        return null;
    }

    boolean matches(BitSet effectiveCpus) {
        if (effectiveCpus.cardinality() != this.cpus.length) {
            return false;
        }
        int index = 0;
        for (int cpu = effectiveCpus.nextSetBit(0); cpu >= 0; cpu = effectiveCpus.nextSetBit(cpu + 1)) {
            if (this.cpus[index++] != cpu) {
                return false;
            }
        }
        return true;
    }

    /// Returns an immutable snapshot of every participating worker.
    public Worker[] workers() {
        Worker[] snapshot = new Worker[this.slots.length];
        for (int i = 0; i < this.slots.length; i++) {
            snapshot[i] = this.slots[i].snapshot();
        }
        return snapshot;
    }

    /// Waits until every participating worker has cleared its running publication.
    public boolean awaitTermination(long deadlineNanos) {
        for (WorkerSlot slot : this.slots) {
            while (slot.running()) {
                if (Thread.currentThread().isInterrupted()) {
                    return false;
                }
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0L) {
                    return false;
                }
                LockSupport.parkNanos(Math.min(5_000L, remaining));
            }
        }
        return true;
    }

    /// A lifecycle snapshot exposing the actual participating thread and running state.
    public record Worker(Thread thread, boolean running) {}

    /// The zero-allocation lifecycle publication slot used by a worker at entry and exit.
    public static final class WorkerSlot {

        private volatile Thread thread;
        private volatile boolean running;

        public Thread thread() {
            return this.thread;
        }

        public boolean running() {
            return this.running;
        }

        public void enter(Thread worker) {
            this.thread = Objects.requireNonNull(worker);
            this.running = true;
        }

        public void exit() {
            this.thread = null;
            this.running = false;
        }

        private Worker snapshot() {
            while (true) {
                boolean before = this.running;
                Thread worker = this.thread;
                boolean after = this.running;
                if (before == after && (!after || worker != null)) {
                    return new Worker(after ? worker : null, after);
                }
                Thread.onSpinWait();
            }
        }
    }
}
