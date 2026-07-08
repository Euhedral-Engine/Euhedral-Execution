package euhedral.benchmarks.utils;

import euhedral.benchmarks.frames.BenchArrayFrame;
import euhedral.benchmarks.frames.MandelbrotPixel;
import euhedral.benchmarks.frames.MandelbulbFrame;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.AbstractExecutor;
import org.openjdk.jmh.infra.Blackhole;

public class FractalExecutor extends AbstractExecutor {

    private final Blackhole blackhole;

    public  FractalExecutor(Blackhole blackhole) {
        super(-1);
        this.blackhole = blackhole;
    }

    FractalExecutor(int cpu, Blackhole blackhole) {
        super(cpu);
        this.blackhole = blackhole;
    }

    @Override
    public void execute(AbstractFrame frame) {
        switch (frame) {
            case MandelbrotPixel fractal -> fractal.cpu = super.cpu;
            case MandelbulbFrame fractal -> fractal.cpu = super.cpu;
            case BenchArrayFrame array -> array.cpu = super.cpu;
            default -> frame.throwCancelSignal();
        }
        frame.execute();
        blackhole.consume(frame);
    }

    @Override
    public AbstractExecutor hookOnClone(int cpu) {
        return new FractalExecutor(cpu, this.blackhole);
    }
}
