package euhedral.benchmarks.utils;

import euhedral.benchmarks.frames.BenchArrayFrame;
import euhedral.benchmarks.frames.MandelbrotPixel;
import euhedral.benchmarks.frames.MandelbulbFrame;
import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.AbstractExecutor;
import org.openjdk.jmh.infra.Blackhole;

public class FractalExecutor extends AbstractExecutor {

    private final Blackhole blackhole;

    public  FractalExecutor(Blackhole blackhole) {
        super(null);
        this.blackhole = blackhole;
    }

    FractalExecutor(PinnedThreadExecutor executor, Blackhole blackhole) {
        super(executor);
        this.blackhole = blackhole;
    }

    @Override
    public void execute(AbstractFrame frame) {
        switch (frame) {
            case MandelbrotPixel fractal -> fractal.cpu = this.executorService.getCpu();
            case MandelbulbFrame fractal -> fractal.cpu = this.executorService.getCpu();
            case BenchArrayFrame array -> array.cpu = this.executorService.getCpu();
            default -> frame.throwMeAsError();
        }
        frame.execute();
        blackhole.consume(frame);
    }

    @Override
    public AbstractExecutor clone(CloneConfig cloneConfig) {
        return new FractalExecutor(super.executorService, this.blackhole);
    }

    @Override
    public AbstractExecutor clone(CloneConfig cloneConfig,
            PinnedThreadExecutor executor) {
        return new FractalExecutor(executor, this.blackhole);
    }
}
