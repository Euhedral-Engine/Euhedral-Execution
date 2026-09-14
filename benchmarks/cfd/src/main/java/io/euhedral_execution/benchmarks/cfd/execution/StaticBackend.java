package io.euhedral_execution.benchmarks.cfd.execution;

import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.solver.StepContext;
import io.euhedral_execution.hardware_utils.ThreadTools;
import java.util.concurrent.locks.LockSupport;

/// Persistent workers own contiguous range sequences; a monitor publishes each generation once.
public final class StaticBackend extends RangeBackend {
    private final Object gate = new Object();
    private final Worker[] workers;
    private long generation;
    private boolean stopping;
    private boolean started;

    public StaticBackend(int[] cpus, boolean affinity, long shutdownMillis) {
        super(shutdownMillis);
        if (cpus.length == 0) {
            throw new IllegalArgumentException("workers must be positive");
        }
        workers = new Worker[cpus.length];
        for (int i = 0; i < workers.length; i++) {
            workers[i] = new Worker(i, cpus[i], affinity);
        }
    }

    @Override
    public void prepare(CfdRangeFrame[] ranges) {
        super.prepare(ranges);
        started = true;
        try {
            for (var worker : workers) {
                worker.from = (int) ((long) ranges.length * worker.ordinal / workers.length);
                worker.to = (int) ((long) ranges.length * (worker.ordinal + 1) / workers.length);
                worker.start();
            }
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    @Override
    protected void dispatch(StepContext context) {
        check(context);
        synchronized (gate) {
            dispatched = true;
            generation++;
            gate.notifyAll();
        }
    }

    @Override
    protected void finishGeneration(StepContext context) {
        for (var worker : workers) {
            while (worker.completed != generation) {
                check(context);
                if (!worker.isAlive()) {
                    throw new IllegalStateException("CFD static worker lost: " + worker.getName());
                }
                LockSupport.parkNanos(10_000);
            }
        }
    }

    @Override
    public void cancel() {
        super.cancel();
        if (!dispatched && ranges != null) {
            for (var frame : ranges) {
                frame.doFinally();
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        cancel();
        synchronized (gate) {
            stopping = true;
            gate.notifyAll();
        }
        boolean interrupted = Thread.interrupted();
        long start = System.nanoTime();
        try {
            if (started) {
                for (var worker : workers) {
                    while (worker.isAlive()) {
                        long remaining = shutdownNs - (System.nanoTime() - start);
                        if (remaining <= 0) {
                            throw new IllegalStateException("CFD static workers did not stop; buffers retained");
                        }
                        try {
                            worker.join(Math.max(1, remaining / 1_000_000));
                        } catch (InterruptedException error) {
                            interrupted = true;
                        }
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final class Worker extends Thread {
        private final int ordinal, cpu;
        private final boolean affinity;
        private volatile long completed;
        private int from, to;

        Worker(int ordinal, int cpu, boolean affinity) {
            super("cfd-static-" + ordinal);
            this.ordinal = ordinal;
            this.cpu = cpu;
            this.affinity = affinity;
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                if (affinity) {
                    ThreadTools.setAffinity(cpu);
                }
                while (true) {
                    long next;
                    synchronized (gate) {
                        while (!stopping && generation == completed) {
                            gate.wait();
                        }
                        if (stopping) {
                            return;
                        }
                        next = generation;
                    }
                    for (int i = from; i < to; i++) {
                        runFrame(ranges[i]);
                    }
                    /// Release publication covers task completion, including all accesses after terminal hooks.
                    completed = next;
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                if (affinity) {
                    ThreadTools.releaseAffinity();
                }
            }
        }
    }
}
