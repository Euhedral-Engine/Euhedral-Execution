package euhedral.benchmarks.frames;

import euhedral.atomics.PaddedLongAdder;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.impl.FrameManager;

public abstract class FractalFrame extends AbstractFrame {

    protected final int width;
    protected final int height;
    protected final int iterationCap;
    protected final double[] magnitudes;
    protected final int[] escapes;

    public final PaddedLongAdder counters;

    public FractalFrame(long idHash, FrameManager<Void, FractalFrame> recycler, int width,
            int height, int iterationCap, double[] magnitudes, int[] escapes, PaddedLongAdder counters) {
        super(idHash, recycler);
        this.width = width;
        this.height = height;
        this.iterationCap = iterationCap;
        this.magnitudes = magnitudes;
        this.escapes = escapes;
        this.counters = counters;
    }

    public abstract int compute();

    public boolean isOrdered() {
        return false;
    }
}
