package io.euhedral_execution.core.config;

import java.util.BitSet;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/// Publishes the live worker identities belonging to one clone, including its optional SMT buddy.
public final class CloneLivenessRegistry {

    private final BitSet cpus;
    private final WorkerSlot[] slots;

    public CloneLivenessRegistry(BitSet effectiveCpus) {
        Objects.requireNonNull(effectiveCpus);
        this.cpus = (BitSet) effectiveCpus.clone();
        this.slots = new WorkerSlot[effectiveCpus.length()];
        for (int cpu = effectiveCpus.nextSetBit(0); cpu >= 0; cpu = effectiveCpus.nextSetBit(cpu + 1)) {
            this.slots[cpu] = new WorkerSlot();
        }
    }

    /// Returns the mutable publication slot for one participating logical CPU.
    public WorkerSlot slot(int cpu) {
        return cpu >= 0 && cpu < this.slots.length ? this.slots[cpu] : null;
    }

    boolean matches(BitSet effectiveCpus) {
        return this.cpus.equals(effectiveCpus);
    }

    /// Returns an immutable snapshot of every participating worker.
    public Worker[] workers() {
        Worker[] snapshot = new Worker[this.cpus.cardinality()];
        int index = 0;
        for (int cpu = this.cpus.nextSetBit(0); cpu >= 0; cpu = this.cpus.nextSetBit(cpu + 1)) {
            snapshot[index++] = this.slots[cpu].snapshot();
        }
        return snapshot;
    }

    /// Waits until every participating worker has cleared its running publication.
    public boolean awaitTermination(long deadlineNanos) {
        for (int cpu = this.cpus.nextSetBit(0); cpu >= 0; cpu = this.cpus.nextSetBit(cpu + 1)) {
            WorkerSlot slot = this.slots[cpu];
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
