package euhedral.io.frames;

import euhedral.io.impl.FrameManager;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("unused")
public final class CollectionFrame extends AbstractFrame {
    private final AtomicBoolean killSwitch;

    private Collection<AbstractFrame> frames;

    public CollectionFrame(long idHash, Collection<AbstractFrame> frames) {
        this(idHash, frames, null, null);
    }

    public CollectionFrame(long idHash, Collection<AbstractFrame> frames, AtomicBoolean killSwitch) {
        this(idHash, frames, killSwitch, null);
    }

    public CollectionFrame(long idHash, Collection<AbstractFrame> frames, AtomicBoolean killSwitch,
            FrameManager<AbstractFrame[], ArrayFrame> frameManager) {
        super(idHash, frameManager);
        Objects.requireNonNull(frames);

        this.killSwitch = killSwitch;
        this.frames = frames;
    }

    @Override
    public void execute() {
        for (AbstractFrame frame : this.frames) {
            if (frame != null && frame.isAlive()) {
                frame.execute();
            }
        }
    }

    @Override
    public boolean isAlive() {
        if(this.killSwitch != null) {
            return !this.killSwitch.getAcquire();
        }
        return true;
    }

    @Override
    public void kill() {
        if(this.killSwitch != null) {
            this.killSwitch.setRelease(true);
        }
    }

    public void replace(Collection<AbstractFrame> frames) {
        Objects.requireNonNull(frames);
        this.frames = frames;
    }
}
