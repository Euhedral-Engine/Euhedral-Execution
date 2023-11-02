package euhedral.io.frames;

import euhedral.io.impl.FrameManager;
import java.util.concurrent.atomic.AtomicBoolean;

public class RunnableFrame extends AbstractFrame {

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
    public long getSizeBytes() {
        return 64;
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
            killSwitch.set(true);
        }
    }
}
