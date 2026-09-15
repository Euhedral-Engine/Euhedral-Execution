package io.euhedral_execution.benchmarks.cfd.execution;

import io.euhedral_execution.benchmarks.cfd.frames.CfdRangeFrame;
import io.euhedral_execution.benchmarks.cfd.solver.FlowDiagnostics;
import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import io.euhedral_execution.benchmarks.cfd.solver.StepContext;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

abstract class RangeBackend implements ExecutionBackend {
    protected CfdRangeFrame[] ranges;
    protected final long shutdownNs;
    protected boolean closed;
    protected boolean dispatched;
    private boolean failed;

    RangeBackend(long shutdownMillis) {
        if (shutdownMillis <= 0 || shutdownMillis > Long.MAX_VALUE / 1_000_000) {
            throw new IllegalArgumentException("shutdown timeout must be positive and fit nanoseconds");
        }
        shutdownNs = shutdownMillis * 1_000_000;
    }

    @Override
    public void prepare(RangePlan plan) {
        CfdRangeFrame[] frames = plan.createFrames();
        if (ranges != null || closed) {
            throw new IllegalStateException("backend already prepared or closed");
        }
        Objects.requireNonNull(frames);
        if (frames.length == 0) {
            throw new IllegalArgumentException("a backend needs at least one fluid range");
        }
        int previous = -1;
        for (var frame : frames) {
            if (frame.rangeId() <= previous) {
                throw new IllegalArgumentException("range ordinals must be unique and increasing");
            }
            previous = frame.rangeId();
        }
        ranges = frames;
    }

    @Override
    public final void execute(StepContext context, FlowDiagnostics diagnostics) {
        if (ranges == null || closed || failed) {
            throw new IllegalStateException("backend unavailable");
        }
        for (var frame : ranges) {
            frame.replace(context);
        }
        dispatched = false;
        try {
            dispatch(context);
            for (var frame : ranges) {
                while (!frame.isDone()) {
                    check(context);
                    LockSupport.parkNanos(10_000);
                }
                frame.requireSuccess();
            }
            finishGeneration(context);
            diagnostics.reduce(context.step(), ranges);
            check(context);
        } catch (RuntimeException | Error error) {
            failed = true;
            cancel();
            try {
                quiesce();
            } catch (RuntimeException secondary) {
                error.addSuppressed(secondary);
            }
            throw error;
        }
    }

    protected abstract void dispatch(StepContext context);

    @Override
    public long framesPreallocated() {
        return ranges == null ? 0 : ranges.length;
    }

    protected void finishGeneration(StepContext context) {}

    protected final void check(StepContext context) {
        try {
            context.checkProgress(0, 0, 0);
        } catch (SimulationException error) {
            var detailed = new SimulationException(
                    context.step(), 0, 0, 0, error.getMessage() + "; outstanding range ordinals: " + outstanding());
            detailed.initCause(error);
            throw detailed;
        }
    }

    private String outstanding() {
        var ids = new StringBuilder();
        int count = 0;
        for (var frame : ranges) {
            if (!frame.isDone()) {
                if (count++ < 32) {
                    ids.append(frame.rangeId()).append(' ');
                }
            }
        }
        return ids.append("(total ").append(count).append(')').toString();
    }

    @Override
    public void cancel() {
        if (ranges != null) {
            for (var frame : ranges) {
                frame.kill();
            }
        }
    }

    protected void quiesce() {
        long start = System.nanoTime();
        boolean interrupted = Thread.interrupted();
        try {
            if (ranges != null) {
                for (var frame : ranges) {
                    while (!frame.isDone()) {
                        if (System.nanoTime() - start >= shutdownNs) {
                            throw new IllegalStateException(
                                    "CFD workers did not quiesce; retained buffers; outstanding range ordinals: "
                                            + outstanding());
                        }
                        LockSupport.parkNanos(10_000);
                        interrupted |= Thread.interrupted();
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /// FJP and static workers use the same frame lifecycle as the ordinary Euhedral executor.
    static void runFrame(CfdRangeFrame frame) {
        try {
            frame.execute();
            frame.doFinally();
        } catch (Throwable error) {
            frame.doFinallyWithError(error);
        }
    }
}
