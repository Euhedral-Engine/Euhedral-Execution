package euhedral.io.generics;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.flow_control.BufferedBridge;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.CancelFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The terminal execution sink of Euhedral Core
///
/// `AbstractExecutor` is the final execution boundary for frames. It receives scheduled work,
/// executes it, and forwards completed frames to the completion channel.
///
/// Execution is intentionally minimal and without side effects. Completed frames are always pushed
/// into the completion sink, which decouples execution from downstream acknowledgment.
public abstract class AbstractExecutor implements PipelineExecutor {

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected final PinnedThreadExecutor executorService;
    private BufferedBridge completeSink;

    public AbstractExecutor(PinnedThreadExecutor executorService) {
        this.executorService = executorService;
    }

    @Override
    public final void reportCompletionsTo(CloneableObject clone) {
        this.completeSink = clone.completeChannel();
    }

    @Override
    public void input(LaticeSource stream) {
        stream.addDownstream(new ExecutionTerminal());
    }

    private void executeInternal(AbstractFrame frame) {
        try {
            if (!frame.isAlive()) {
                frame.throwMeAsError();
            }
            execute(frame);
        } catch (Exception e) {
            frame.setCancelledExecution(true);
            if (!(e instanceof CancelFrame)) {
                logger.error("Uncaught exception while executing frame. {}", frame, e);
            }
        }

        while (!this.completeSink.offer(frame)) {
            Thread.onSpinWait();
        }
    }

    @Override
    public abstract AbstractExecutor clone(CloneConfig cloneConfig);

    @Override
    public abstract AbstractExecutor clone(CloneConfig cloneConfig, PinnedThreadExecutor executor);

    @Override
    public void close() {
    }

    protected class ExecutionTerminal implements LatticeReceiver {

        public ExecutionTerminal() {

        }

        @Override
        public void addUpstream(LaticeSource stream) {
            stream.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AbstractFrame frame) {
            executeInternal(frame);
        }

        @Override
        public void onError(Throwable throwable) {

        }

        @Override
        public void onComplete() {

        }
    }
}
