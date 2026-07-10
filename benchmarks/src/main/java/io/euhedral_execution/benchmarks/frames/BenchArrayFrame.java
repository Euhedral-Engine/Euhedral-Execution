package io.euhedral_execution.benchmarks.frames;

import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;

public class BenchArrayFrame extends AbstractFrame {

    protected final AbstractFrame[] frames;

    private final PaddedLongAdder counters;
    public int cpu;

    public BenchArrayFrame(long idHash, AbstractFrame[] frames, PaddedLongAdder counters) {
        super(idHash);

        this.frames = frames;
        this.counters = counters;
    }

    @Override
    public void execute() {
        for (AbstractFrame frame : this.frames) {
            frame.execute();
        }
    }

    @Override
    public boolean isAlive() {
        return true;
    }

    @Override
    public void kill() {

    }

    @Override
    public void doFinally() {
        this.counters.getAndAdd(cpu, 4L * this.frames.length);
    }
}
