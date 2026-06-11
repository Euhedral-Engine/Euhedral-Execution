package euhedral.io.frames;

import euhedral.io.impl.FrameManager;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("unused")
public final class ArrayFrame extends AbstractFrame {

    private final AtomicBoolean killSwitch;

    private AbstractFrame[] frames;
    private long sizeBytes;

    public ArrayFrame(long idHash, AbstractFrame[] frames) {
        this(idHash, frames, null, null);
    }

    public ArrayFrame(long idHash, AbstractFrame[] frames, AtomicBoolean killSwitch) {
        this(idHash, frames, killSwitch, null);
    }

    public ArrayFrame(long idHash, AbstractFrame[] frames, AtomicBoolean killSwitch,
            FrameManager<AbstractFrame[], ArrayFrame> frameManager) {
        super(idHash, frameManager);
        Objects.requireNonNull(frames);

        this.killSwitch = killSwitch;
        replace(frames);
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
    public long getSizeBytes() {
        return sizeBytes;
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

    public void replace(AbstractFrame[] frames) {
        Objects.requireNonNull(frames);
        this.frames = frames;
        long sizeBytes = 0;
        for (AbstractFrame frame : frames) {
            if(frame != null) {
                sizeBytes += frame.getSizeBytes();
            }
        }
        this.sizeBytes = sizeBytes;
    }
}
