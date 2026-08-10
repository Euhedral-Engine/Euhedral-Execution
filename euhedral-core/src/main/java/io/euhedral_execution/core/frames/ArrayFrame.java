package io.euhedral_execution.core.frames;

import io.euhedral_execution.core.impl.FrameManager;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("unused")
public final class ArrayFrame extends AbstractFrame {

    private AbstractFrame[] frames;

    public ArrayFrame(long idHash, AbstractFrame[] frames) {
        this(idHash, frames, null, null);
    }

    public ArrayFrame(long idHash, AbstractFrame[] frames, AtomicBoolean killSwitch) {
        this(idHash, frames, null, killSwitch);
    }

    public ArrayFrame(
            long idHash,
            AbstractFrame[] frames,
            FrameManager<AbstractFrame[], ArrayFrame> frameManager,
            AtomicBoolean killSwitch) {
        super(idHash, frameManager, killSwitch);
        Objects.requireNonNull(frames);

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

    public void replace(AbstractFrame[] frames) {
        Objects.requireNonNull(frames);
        this.frames = frames;
    }
}
