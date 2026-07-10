package io.euhedral_execution.core.impl;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;

/// The minimal implementation of an [AbstractExecutor]
///
/// Simply hits `execute()` on a frame.
public final class DefaultExecutor extends AbstractExecutor {

    public DefaultExecutor() {
        super(-1);
    }

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
