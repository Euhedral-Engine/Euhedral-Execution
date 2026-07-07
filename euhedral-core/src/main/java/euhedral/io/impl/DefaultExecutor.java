package euhedral.io.impl;

import euhedral.io.config.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.AbstractExecutor;

/// The minimal implementation of an [AbstractExecutor]
///
/// Simply hits `execute()` on a frame.
public final class DefaultExecutor extends AbstractExecutor {

    public DefaultExecutor(int cpu) {
        super(cpu);
    }

    @Override
    public void execute(AbstractFrame frame) {
        frame.execute();
    }

    @Override
    public AbstractExecutor hookOnClone(int cpu) {
        return new DefaultExecutor(cpu);
    }
}
