package euhedral.io.impl;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.AbstractExecutor;

/// The minimal implementation of an [AbstractExecutor]
///
/// Simply hits `execute()` on a frame.
public final class DefaultExecutor extends AbstractExecutor {

    public DefaultExecutor(PinnedThreadExecutor executorService) {
        super(executorService);
    }

    @Override
    public void execute(AbstractFrame frame) {
        frame.execute();
    }

    @Override
    public AbstractExecutor clone(CloneConfig cloneConfig) {
        return new DefaultExecutor(this.executorService);
    }

    @Override
    public AbstractExecutor clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
        return new DefaultExecutor(executor);
    }
}
