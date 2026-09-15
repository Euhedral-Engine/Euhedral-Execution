package io.euhedral_execution.benchmarks.cfd.frames;

import io.euhedral_execution.benchmarks.cfd.solver.SimulationException;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.impl.FrameManager;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicBoolean;

/// A context-owning CFD work unit. Euhedral invokes the body and exactly one terminal hook.
/// Range-result writes precede the terminal release publication; the next owner must observe
/// that publication with acquire before reading results. Terminal hooks then return the frame to its manager;
/// only the manager owner may reacquire and replace it, after consuming the previous result.
public abstract class CfdFrame extends AbstractFrame {
    public enum Status {
        NEW,
        PREPARING,
        READY,
        EXECUTING,
        FINALIZING,
        SUCCEEDED,
        CANCELLED,
        FAILED
    }

    private static final VarHandle STATUS;

    static {
        try {
            STATUS = MethodHandles.lookup().findVarHandle(CfdFrame.class, "status", Status.class);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private Status status = Status.NEW;
    /// The executing worker also owns its terminal hook; this flag is never polled by the driver.
    private boolean bodyComplete;
    private RuntimeException failure;

    protected CfdFrame(long idHash, FrameManager<?, ?> recycler) {
        super(idHash, recycler, new AtomicBoolean());
    }

    protected final void beginPreparation() {
        Status previous = (Status) STATUS.getAcquire(this);
        if (previous != Status.NEW
                && previous != Status.SUCCEEDED
                && previous != Status.CANCELLED
                && previous != Status.FAILED) {
            throw new IllegalStateException(
                    "frame can only be replaced before first dispatch or after terminal completion");
        }
        /// Replacement has one owner. Acquire above consumes the previous terminal release;
        /// manager-backed frames must also have been dequeued from their recycler first.
        STATUS.setOpaque(this, Status.PREPARING);
        bodyComplete = false;
        failure = null;
        killSwitch.set(false);
    }

    protected final void ready() {
        STATUS.setRelease(this, Status.READY);
    }

    public final Status status() {
        return (Status) STATUS.getAcquire(this);
    }

    public final boolean isDone() {
        Status current = (Status) STATUS.getAcquire(this);
        return current == Status.SUCCEEDED || current == Status.CANCELLED || current == Status.FAILED;
    }

    public final void requireSuccess() {
        Status current = (Status) STATUS.getAcquire(this);
        if (current == Status.SUCCEEDED) {
            return;
        }
        if (current == Status.CANCELLED || current == Status.FAILED) {
            throw failure;
        }
        throw new IllegalStateException("frame has not reached terminal completion: " + current);
    }

    @Override
    public final void execute() {
        if (!STATUS.compareAndSet(this, Status.READY, Status.EXECUTING)) {
            throw new IllegalStateException("frame must be prepared and dispatched exactly once");
        }
        if (!isAlive()) {
            throwCancelSignal();
        }
        try {
            executeBody();
            bodyComplete = true;
        } catch (Error error) {
            /// The ordinary executor catches Exception only. Publish failure before losing its worker.
            doFinallyWithError(error);
            throw error;
        }
    }

    protected abstract void executeBody();

    protected abstract void publishSuccess();

    protected abstract long generation();

    @Override
    public final void doFinally() {
        Status previous = claimTerminal();
        if (previous == null) {
            return;
        }
        if (!bodyComplete || !isAlive()) {
            failure = new SimulationException(generation(), 0, 0, 0, "frame cancelled");
            publishTerminal(Status.CANCELLED);
            return;
        }
        try {
            publishSuccess();
        } catch (RuntimeException | Error error) {
            failure = executionFailure(error);
            publishTerminal(Status.FAILED);
            if (error instanceof Error fatal) {
                throw fatal;
            }
            return;
        }
        publishTerminal(Status.SUCCEEDED);
    }

    @Override
    public final void doFinallyWithError(Throwable error) {
        if (claimTerminal() == null) {
            return;
        }
        if (error instanceof CancelSignal) {
            failure = new SimulationException(generation(), 0, 0, 0, "frame cancelled");
            publishTerminal(Status.CANCELLED);
        } else {
            failure = executionFailure(error);
            publishTerminal(Status.FAILED);
        }
    }

    private RuntimeException executionFailure(Throwable error) {
        if (error instanceof RuntimeException runtime) {
            return runtime;
        }
        var failure = new SimulationException(generation(), 0, 0, 0, "frame execution failed");
        failure.initCause(error);
        return failure;
    }

    private void publishTerminal(Status terminal) {
        /// Publish results before returning ownership through the manager's MPSC recycler.
        /// No mutable frame access is allowed after enqueueing: replacement may start immediately.
        STATUS.setRelease(this, terminal);
        try {
            completed(terminal, failure);
        } finally {
            recycle();
        }
    }

    /// Called once by the completion producer before recycling; implementations must not throw.
    protected void completed(Status terminal, RuntimeException failure) {}

    private Status claimTerminal() {
        Status previous = (Status) STATUS.getAcquire(this);
        if (previous != Status.READY && previous != Status.EXECUTING) {
            return null;
        }
        return STATUS.compareAndSet(this, previous, Status.FINALIZING) ? previous : null;
    }
}
