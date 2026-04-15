package euhedral.io.impl;

import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.KeyHasher;
import java.util.concurrent.ThreadLocalRandom;

public class FrameFactory<T, F extends AbstractFrame> {
    private final long idHash = KeyHasher.mix(ThreadLocalRandom.current().nextLong());

    private final FrameCreate<T, F> frameGenerator;
    private final FrameReplace<T, F> frameReplace;

    private long seed = ThreadLocalRandom.current().nextLong();

    public FrameFactory(FrameCreate<T, F> frameGenerator, FrameReplace<T, F> frameReplace) {
        this.frameGenerator = frameGenerator;
        this.frameReplace = frameReplace;
    }

    public F create(T data) {
        F frame = frameGenerator.create(idHash, data);
        if(frame.isOrdered()) {
            frame.randomizeHash(idHash);
        } else {
            frame.randomizeHash(seed++);
        }
        return frame;
    }

    public void replace(T data, F frame) {
        frame.reset();

        frameReplace.replace(data, frame);
        if(frame.isOrdered()) {
            frame.randomizeHash(idHash);
        } else {
            frame.randomizeHash(seed++);
        }
    }

    @FunctionalInterface
    public interface FrameCreate<T, F extends AbstractFrame> {
        F create(long idHash, T data);
    }

    @FunctionalInterface
    public interface FrameReplace<T, F extends AbstractFrame> {
        void replace(T data, F oldFrame);
    }
}
