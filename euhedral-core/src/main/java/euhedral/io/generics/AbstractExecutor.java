package euhedral.io.generics;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.flow_control.BufferedBridge;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.CancelFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public void input(ScaffoldingSource stream) {
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
            if(!(e instanceof CancelFrame)) {
                logger.error("Uncaught exception while executing frame. {}", frame, e);
            }
        }

        int cycles = 0;
        while (!this.completeSink.offer(frame)) {
            if((cycles++ & 127) == 0) {
                Thread.onSpinWait();
            } else if(cycles >= 512) {
                Thread.yield();
            }
        }
    }

    @Override
    public abstract AbstractExecutor clone(CloneConfig cloneConfig);

    @Override
    public abstract AbstractExecutor clone(CloneConfig cloneConfig, PinnedThreadExecutor executor);

    @Override
    public void close() {
    }

    protected class ExecutionTerminal implements ScaffoldingTerminal {

        public ExecutionTerminal() {

        }

        @Override
        public void addUpstream(ScaffoldingSource stream) {
            stream.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(AbstractFrame frame) {
            if (frame.isUseVThread()) {
                executorService.vThread(() -> executeInternal(frame));
            } else {
                executeInternal(frame);
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
