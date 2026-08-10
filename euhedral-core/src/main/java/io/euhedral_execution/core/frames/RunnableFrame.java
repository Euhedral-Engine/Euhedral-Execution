package io.euhedral_execution.core.frames;

import io.euhedral_execution.core.impl.FrameManager;
import java.util.concurrent.atomic.AtomicBoolean;

/// A generic frame that runs a function.
@SuppressWarnings("unused")
public final class RunnableFrame extends AbstractFrame {

    private final Runnable runnable;

    public RunnableFrame(long idHash, Runnable runnable) {
        super(idHash);
        this.runnable = runnable;
    }

    public RunnableFrame(
            long idHash, Runnable runnable, FrameManager<Void, RunnableFrame> recycler, AtomicBoolean killSwitch) {
        super(idHash, recycler, killSwitch);
        this.runnable = runnable;
    }

    @Override
    public void execute() {
        runnable.run();
    }
}
