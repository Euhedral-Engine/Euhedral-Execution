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

    public void run() {
        runnable.run();
    }

    @Override
    public long getSizeBytes() {
        return 256;
    }

    @Override
    public boolean isAlive() {
        return !killSwitch.get();
    }

    @Override
    public void kill() {
        killSwitch.set(true);
    }
}
