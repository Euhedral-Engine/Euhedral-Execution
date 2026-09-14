package io.euhedral_execution.benchmarks.cfd.execution;

import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.solver.StepContext;
import io.euhedral_execution.hardware_utils.ThreadTools;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/// A retained bulk task tree. Only the external driver submits the root each generation.
public final class ForkJoinBackend extends RangeBackend {
    private final ForkJoinPool pool;
    private Node root;
    private boolean submitted;

    public ForkJoinBackend(int[] cpus, boolean affinity, long shutdownMillis) {
        super(shutdownMillis);
        if (cpus.length == 0) {
            throw new IllegalArgumentException("workers must be positive");
        }
        int[] assignments = cpus.clone();
        var next = new AtomicInteger();
        pool = new ForkJoinPool(
                cpus.length,
                owner -> new ForkJoinWorkerThread(owner) {
                    private final int cpu = assignments[Math.floorMod(next.getAndIncrement(), assignments.length)];

                    @Override
                    protected void onStart() {
                        super.onStart();
                        if (affinity) {
                            ThreadTools.setAffinity(cpu);
                        }
                    }

                    @Override
                    protected void onTermination(Throwable error) {
                        try {
                            if (affinity) {
                                ThreadTools.releaseAffinity();
                            }
                        } finally {
                            super.onTermination(error);
                        }
                    }
                },
                null,
                false,
                cpus.length,
                cpus.length,
                0,
                ignored -> true,
                60,
                TimeUnit.SECONDS);
    }

    @Override
    public void prepare(CfdRangeFrame[] ranges) {
        super.prepare(ranges);
        root = new Node(0, ranges.length);
    }

    @Override
    protected void dispatch(StepContext context) {
        check(context);
        root.reset();
        pool.execute(root);
        submitted = true;
        dispatched = true;
    }

    @Override
    protected void finishGeneration(StepContext context) {
        while (!root.isDone()) {
            check(context);
            LockSupport.parkNanos(10_000);
        }
        root.join();
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
        pool.shutdown();
        boolean interrupted = Thread.interrupted();
        try {
            long start = System.nanoTime();
            while (!pool.isTerminated()) {
                long remaining = shutdownNs - (System.nanoTime() - start);
                if (remaining <= 0) {
                    pool.shutdownNow();
                    throw new IllegalStateException("CFD fork/join workers did not stop; buffers retained");
                }
                try {
                    pool.awaitTermination(remaining, TimeUnit.NANOSECONDS);
                } catch (InterruptedException error) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private final class Node extends RecursiveAction {
        private final int from, to;
        private final Node left, right;

        Node(int from, int to) {
            this.from = from;
            this.to = to;
            int middle = (from + to) >>> 1;
            left = to - from > 1 ? new Node(from, middle) : null;
            right = left == null ? null : new Node(middle, to);
        }

        void reset() {
            /// Successful generation completion joins the entire tree before any reinitialization.
            if (submitted && !isDone()) {
                throw new IllegalStateException("fork/join task still in flight");
            }
            reinitialize();
            if (left != null) {
                left.reset();
                right.reset();
            }
        }

        @Override
        protected void compute() {
            if (left == null) {
                for (int i = from; i < to; i++) {
                    runFrame(ranges[i]);
                }
            } else {
                left.fork();
                right.invoke();
                left.join();
            }
        }
    }
}
