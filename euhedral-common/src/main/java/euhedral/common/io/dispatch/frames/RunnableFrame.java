package euhedral.common.io.dispatch.frames;

import euhedral.common.io.dispatch.utils.MpscFrameRecycler;
import java.util.concurrent.atomic.AtomicBoolean;

public class RunnableFrame extends AbstractFrame {

    private final String id;

    private final Runnable runnable;
    private final AtomicBoolean killSwitch;

    public RunnableFrame(String id, long idHash, long destinationHash, Runnable runnable, AtomicBoolean killSwitch,
            MpscFrameRecycler recycler) {
        super(idHash, destinationHash, recycler);
        this.id = id;
        this.runnable = runnable;
        this.killSwitch = killSwitch;
    }

    public void run() {
        runnable.run();
    }

    @Override
    public String getId() {
        return id;
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
