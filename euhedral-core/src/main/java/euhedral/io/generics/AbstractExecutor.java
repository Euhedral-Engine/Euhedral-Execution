package euhedral.io.generics;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The terminal execution sink of Euhedral Core
///
/// `AbstractExecutor` is the final execution boundary for frames. It receives,
/// executes, and forwards completed frames to the completion channel.
///
/// Execution is intentionally minimal and without side effects. Completed frames are always pushed
/// into the completion sink, which decouples execution from downstream acknowledgment.
public abstract class AbstractExecutor implements CloneableObject {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected final int cpu;

    public AbstractExecutor(int cpu) {
        this.cpu = cpu;
    }

    @Override
    public void input(LatticeSource stream) {
        stream.addDownstream(new ExecutionTerminal());
    }

    public abstract void execute(AbstractFrame frame);

    private void executeInternal(AbstractFrame frame) {
        try {
            if (!frame.isAlive()) {
                frame.throwCancelSignal();
            }
            execute(frame);
        } catch (Throwable t) {
            if (!(t instanceof AbstractFrame.CancelSignal)) {
                logger.error("Uncaught exception while executing frame. {}", frame, t);
                frame.doFinallyWithError(t);
                return;
            }
        }

        frame.doFinally();
    }

    @Override
    public abstract AbstractExecutor clone(CloneConfig cloneConfig);

    @Override
    public AbstractExecutor clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
        return clone(cloneConfig);
    }

    @Override
    public void close() {
    }

    private class ExecutionTerminal implements LatticeReceiver {

        public ExecutionTerminal() {

        }

        @Override
        public void addUpstream(LatticeSource stream) {
            stream.request(Long.MAX_VALUE);
        }

        @Override
        public void push(AbstractFrame frame) {
            try {
                executeInternal(frame);
            } catch (Throwable t) {
                logger.error("Uncaught exception while running doFinally() on frame. {}", frame, t);
            }
        }

        @Override
        public void onError(Throwable throwable) {

        }

        @Override
        public void onComplete() {

        }
    }
}
