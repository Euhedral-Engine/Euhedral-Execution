package euhedral.benchmarks.pipelines;

import euhedral.benchmarks.frames.MandelbrotPixel;
import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.generics.AbstractExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.config.DRRConfig;
import euhedral.io.config.ExecutionManagerConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.impl.DefaultCloneablePipeline;
import org.openjdk.jmh.infra.Blackhole;

public class FractalPipeline extends DefaultCloneablePipeline {

    public FractalPipeline(String name, DRRConfig drrConfig, ExecutionManagerConfig emConfig, Blackhole blackhole) {
        super(name, drrConfig, emConfig, new FractalExecutor(null, blackhole));
    }

    private static class FractalExecutor extends AbstractExecutor {
        private final Blackhole blackhole;

        public FractalExecutor(PinnedThreadExecutor executor, Blackhole blackhole) {
            super(executor);
            this.blackhole = blackhole;
        }

        @Override
        public void execute(AbstractFrame frame) {
            if(frame instanceof MandelbrotPixel fractal) {
                int escape = fractal.compute();
                blackhole.consume(escape);
                blackhole.consume(fractal);
                fractal.cpu = this.executorService.getCpu();
            } else {
                frame.throwMeAsError();
            }
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
}
