package euhedral.benchmarks.frames;

import euhedral.atomics.PaddedLongAdder;
import euhedral.io.frames.AbstractFrame;

public class NoOpFrame extends AbstractFrame {

    public final PaddedLongAdder counters;

    public Runnable trigger;
    public int cpu;

    public static NoOpFrame[] generate(long idHash, int length, PaddedLongAdder counters) {
        NoOpFrame[] frames = new NoOpFrame[length];
        for(int i = 0; i < length; i++) {
            frames[i] = new NoOpFrame(idHash, counters);
        }
        return frames;
    }

    public NoOpFrame(long idHash, PaddedLongAdder counters) {
        super(idHash, null);
        this.counters = counters;
        setOrdered(false);
    }

    @Override
    public long getSizeBytes() {
        return 64;
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
        if(trigger != null) {
            trigger.run();
        }
        if(counters != null) {
            counters.increment(cpu);
        }
    }
}
