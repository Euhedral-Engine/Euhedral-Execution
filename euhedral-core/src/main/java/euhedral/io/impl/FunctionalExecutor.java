package euhedral.io.impl;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.AbstractExecutor;
import euhedral.io.control_plane.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.ConsumerFrame;
import euhedral.io.frames.FunctionFrame;
import euhedral.io.frames.RunnableFrame;
import euhedral.io.frames.SequencedFrame;

public class FunctionalExecutor extends AbstractExecutor {

    FunctionalExecutor(PinnedThreadExecutor executorService) {
        super(executorService);
    }

    @Override
    public void execute(AbstractFrame frame) {
        if (!frame.isAlive()) {
            frame.throwMeAsError();
        }

        if (frame instanceof SequencedFrame s) {
            s.apply();
        } else if (frame instanceof ConsumerFrame c) {
            c.consume();
        } else if (frame instanceof FunctionFrame f) {
            f.apply();
        } else if (frame instanceof RunnableFrame r) {
            r.run();
        } else {
            frame.throwMeAsError();
        }
    }

    @Override
    public FunctionalExecutor clone(CloneConfig cloneConfig) {
        return new FunctionalExecutor(executorService);
    }

    @Override
    public FunctionalExecutor clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
        return new FunctionalExecutor(executor);
    }

}
