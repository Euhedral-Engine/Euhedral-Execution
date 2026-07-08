package io.euhedral_execution.core.frames;

import io.euhedral_execution.core.impl.FrameManager;
import java.util.concurrent.atomic.AtomicBoolean;

/// A generic frame that runs a function.
@SuppressWarnings("unused")
public final class RunnableFrame extends AbstractFrame {

    private final Runnable runnable;
    private final AtomicBoolean killSwitch;

    public RunnableFrame(long idHash, Runnable runnable, AtomicBoolean killSwitch,
            FrameManager<Void, RunnableFrame> recycler) {
        super(idHash, recycler);
        this.runnable = runnable;
        this.killSwitch = killSwitch;
    }

    @Override
    public void execute() {
        runnable.run();
    }

    @Override
    public boolean isAlive() {
        if(killSwitch != null) {
            return killSwitch.getOpaque();
        }
        return true;
    }

    @Override
    public void kill() {
        if(killSwitch != null) {
            killSwitch.setRelease(true);
        }
    }
}
