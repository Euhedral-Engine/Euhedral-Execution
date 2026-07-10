package io.euhedral_execution.core.frames;

import io.euhedral_execution.core.impl.FrameManager;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("unused")
public final class CollectionFrame extends AbstractFrame {

    private Collection<AbstractFrame> frames;

    public CollectionFrame(long idHash, Collection<AbstractFrame> frames) {
        this(idHash, frames, null, null);
    }

    public CollectionFrame(long idHash, Collection<AbstractFrame> frames,
            AtomicBoolean killSwitch) {
        this(idHash, frames, null, killSwitch);
    }

    public CollectionFrame(long idHash, Collection<AbstractFrame> frames,
            FrameManager<AbstractFrame[], ArrayFrame> frameManager, AtomicBoolean killSwitch) {
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

    public void replace(Collection<AbstractFrame> frames) {
        Objects.requireNonNull(frames);
        this.frames = frames;
    }
}
