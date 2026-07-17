package io.euhedral_execution.core.generics;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The terminal execution sink of Euhedral Core
///
/// `AbstractExecutor` is the final execution boundary for frames. It receives, executes, and
/// forwards completed frames to the completion channel.
///
/// Execution is intentionally minimal and without side effects. Completed frames are always pushed
/// into the completion sink, which decouples execution from downstream acknowledgment.
public abstract class AbstractExecutor implements CloneableObject {

    protected final int cpu;
    private final Logger logger = LoggerFactory.getLogger(
            "euhedral.core" + this.getClass().getSimpleName());

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
                logger.error("Uncaught exception while running doFinally() on frame. {}", frame, e);
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
                logger.error("Uncaught exception while running doFinally. {}", frame);
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
