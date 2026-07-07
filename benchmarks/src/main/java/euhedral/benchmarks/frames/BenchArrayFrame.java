package euhedral.benchmarks.frames;

import euhedral.io.frames.AbstractFrame;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;

public class BenchArrayFrame extends AbstractFrame {

    protected final AbstractFrame[] frames;

    private final PaddedLongAdder counters;
    public int cpu;

    public BenchArrayFrame(long idHash, AbstractFrame[] frames, PaddedLongAdder counters) {
        super(idHash, null);

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
