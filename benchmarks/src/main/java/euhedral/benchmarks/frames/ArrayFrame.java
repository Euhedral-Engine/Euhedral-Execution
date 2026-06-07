package euhedral.benchmarks.frames;

import euhedral.io.frames.AbstractArrayFrame;
import euhedral.io.frames.AbstractFrame;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;

public class ArrayFrame extends AbstractArrayFrame {

    private final PaddedLongAdder counters;
    public int cpu;

    public ArrayFrame(long idHash, AbstractFrame[] frames, PaddedLongAdder counters) {
        super(idHash, frames);
        this.counters = counters;
    }

    public int length() {
        return super.frames.length;
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
        this.counters.getAndAdd(cpu, 4L * super.frames.length);
    }
}
