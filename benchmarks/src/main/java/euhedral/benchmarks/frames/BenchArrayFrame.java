package euhedral.benchmarks.frames;

import euhedral.io.frames.AbstractFrame;
import io.euhedral_execution.data_structures.atomics.PaddedLongAdder;

public class BenchArrayFrame extends AbstractFrame {

    protected final AbstractFrame[] frames;
    protected final long sizeBytes;

    private final PaddedLongAdder counters;
    public int cpu;

    public BenchArrayFrame(long idHash, AbstractFrame[] frames, PaddedLongAdder counters) {
        super(idHash, null);

        this.frames = frames;

        long sizeBytes = 0;
        for (AbstractFrame frame : frames) {
            sizeBytes += frame.getSizeBytes();
        }
        this.sizeBytes = sizeBytes;
        this.counters = counters;
    }

    @Override
    public void execute() {
        for (AbstractFrame frame : this.frames) {
            frame.execute();
        }
    }

    @Override
    public final long getSizeBytes() {
        return sizeBytes;
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
