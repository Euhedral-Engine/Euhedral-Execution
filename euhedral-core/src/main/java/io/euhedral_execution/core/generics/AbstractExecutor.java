package io.euhedral_execution.core.generics;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.internal.Constants;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The terminal execution sink of Euhedral Core
///
/// `AbstractExecutor` receives a frame, checks liveness, and owns its execution and finalization.
///
/// Success and structured cancellation use `doFinally()`; other caught `Exception` values use
/// `doFinallyWithError(Throwable)`. Frame implementations own recycling and notification. There is
/// no separate completion channel, and arbitrary JVM `Error` values are outside this boundary.
public abstract class AbstractExecutor implements CloneableObject {

    protected final int cpu;

    private final Logger logger =
            LoggerFactory.getLogger(Constants.getLoggerName(this.getClass().getSimpleName()));

    /// Creates a production executor with diagnostic body timing disabled.
    protected AbstractExecutor(int cpu) {
        this.cpu = cpu;
    }

    @Override
    public void input(LatticeSource stream) {
        stream.addDownstream(new ExecutionTerminal());
    }

    public abstract void execute(AbstractFrame frame);

    @Override
    public AbstractExecutor clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
        return clone(cloneConfig);
    }

    @Override
    public final AbstractExecutor clone(CloneConfig cloneConfig) {
        return hookOnClone(cloneConfig.effectiveCpus().nextSetBit(0));
    }

    public abstract AbstractExecutor hookOnClone(int cpu);

    private class ExecutionTerminal implements LatticeReceiver {

        @Override
        public void addUpstream(LatticeSource stream) {
            stream.request(Long.MAX_VALUE);
        }

        @Override
        public void push(AbstractFrame frame) {
            try {
                execute(frame);
            } catch (Exception e) {
                // A throwing finalizer may already have transferred the frame to its next owner.
                logger.error("Uncaught exception while running frame finalization", e);
            }
        }

        private void execute(AbstractFrame frame) {
            try {
                if (!frame.isAlive()) {
                    frame.throwCancelSignal();
                }
                AbstractExecutor.this.execute(frame);
            } catch (Exception e) {
                if (!(e instanceof AbstractFrame.CancelSignal)) {
                    logger.error("Uncaught exception while executing frame. {}", frame, e);
                    frame.doFinallyWithError(e);
                    return;
                }
            }

            try {
                frame.doFinally();
            } catch (Exception e) {
                logger.error("Uncaught exception while running doFinally", e);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            // ControlPlaneFragment should never signal an error
        }

        @Override
        public void onComplete() {
            // ControlPlaneFragment should never signal complete
        }
    }
}
