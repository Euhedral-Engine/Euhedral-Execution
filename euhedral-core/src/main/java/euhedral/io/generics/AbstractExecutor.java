package euhedral.io.generics;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.flow_control.BufferedBridge;
import euhedral.io.frames.AbstractFrame;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
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
    private static final VarHandle PRIMED;

    static {
        try {
            PRIMED = MethodHandles.lookup().findVarHandle(AbstractExecutor.class, "primed", boolean.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected final PinnedThreadExecutor executorService;
    private BufferedBridge completeSink;

    private boolean primed = false;

    public AbstractExecutor(PinnedThreadExecutor executorService) {
        this.executorService = executorService;
    }

    public final void setCompletionChannel(CloneableObject clone) {
        if(PRIMED.compareAndSet(this, false, true)) {
            this.completeSink = clone.completeChannel();
        } else {
            throw new IllegalStateException("This executor already has a completion channel");
        }
    }

    @Override
    public void input(LatticeSource stream) {
        stream.addDownstream(new ExecutionTerminal());
    }

    public abstract void execute(AbstractFrame frame);

    private void executeInternal(AbstractFrame frame) {
        try {
            if (!frame.isAlive()) {
                frame.throwMeAsError();
            }
            execute(frame);
        } catch (Exception e) {
            frame.setCancelledExecution(true);
            if (!(e instanceof AbstractFrame.CancelSignal)) {
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

    private class ExecutionTerminal implements LatticeReceiver {

        public ExecutionTerminal() {

        }

        @Override
        public void addUpstream(LatticeSource stream) {
            stream.request(Long.MAX_VALUE);
        }

        @Override
        public void push(AbstractFrame frame) {
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
