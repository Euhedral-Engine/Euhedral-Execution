package io.euhedral_execution.benchmarks.cfd.frames;

import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.impl.FrameManager;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// A context-owning CFD work unit. Euhedral invokes the body and exactly one terminal hook.
/// Range-result writes precede the terminal atomic publication; the next owner must observe
/// that publication before reading results. Terminal hooks then return the frame to its manager;
/// only the manager owner may reacquire and replace it, after consuming the previous result.
public abstract class CfdFrame extends AbstractFrame {
    public enum Status {
        NEW,
        PREPARING,
        READY,
        EXECUTING,
        BODY_COMPLETE,
        FINALIZING,
        SUCCEEDED,
        CANCELLED,
        FAILED
    }

    private final AtomicReference<Status> status = new AtomicReference<>(Status.NEW);
    private RuntimeException failure;

    protected CfdFrame(long idHash, FrameManager<?, ?> recycler) {
        super(idHash, recycler, new AtomicBoolean());
    }

    protected final void beginPreparation() {
        Status previous = status.get();
        if ((previous != Status.NEW
                        && previous != Status.SUCCEEDED
                        && previous != Status.CANCELLED
                        && previous != Status.FAILED)
                || !status.compareAndSet(previous, Status.PREPARING))
            throw new IllegalStateException(
                    "frame can only be replaced before first dispatch or after terminal completion");
        failure = null;
        killSwitch.set(false);
    }

    protected final void ready() {
        status.set(Status.READY);
    }

    public final Status status() {
        return status.get();
    }

    public final boolean isDone() {
        Status current = status.get();
        return current == Status.SUCCEEDED || current == Status.CANCELLED || current == Status.FAILED;
    }

    public final void requireSuccess() {
        Status current = status.get();
        if (current == Status.SUCCEEDED) return;
        if (current == Status.CANCELLED || current == Status.FAILED) throw failure;
        throw new IllegalStateException("frame has not reached terminal completion: " + current);
    }

    @Override
    public final void execute() {
        if (!status.compareAndSet(Status.READY, Status.EXECUTING))
            throw new IllegalStateException("frame must be prepared and dispatched exactly once");
        if (!isAlive()) throwCancelSignal();
        executeBody();
        status.set(Status.BODY_COMPLETE);
    }

    protected abstract void executeBody();

    protected abstract void publishSuccess();

    protected abstract long generation();

    @Override
    public final void doFinally() {
        Status previous = claimTerminal();
        if (previous == null) return;
        if (previous != Status.BODY_COMPLETE || !isAlive()) {
            failure = new SimulationException(generation(), 0, 0, 0, "frame cancelled");
            publishTerminal(Status.CANCELLED);
            return;
        }
        try {
            publishSuccess();
        } catch (RuntimeException e) {
            failure = e;
            publishTerminal(Status.FAILED);
            return;
        }
        publishTerminal(Status.SUCCEEDED);
    }

    @Override
    public final void doFinallyWithError(Throwable error) {
        if (claimTerminal() == null) return;
        if (error instanceof CancelSignal) {
            failure = new SimulationException(generation(), 0, 0, 0, "frame cancelled");
            publishTerminal(Status.CANCELLED);
        } else {
            if (error instanceof RuntimeException runtime) failure = runtime;
            else {
                failure = new SimulationException(generation(), 0, 0, 0, "frame execution failed");
                failure.initCause(error);
            }
            publishTerminal(Status.FAILED);
        }
    }

    private void publishTerminal(Status terminal) {
        /// Publish results before returning ownership through the manager's MPSC recycler.
        /// No mutable frame access is allowed after enqueueing: replacement may start immediately.
        status.set(terminal);
        recycle();
    }

    private Status claimTerminal() {
        Status previous = status.get();
        if (previous != Status.READY && previous != Status.EXECUTING && previous != Status.BODY_COMPLETE) return null;
        return status.compareAndSet(previous, Status.FINALIZING) ? previous : null;
    }
}
