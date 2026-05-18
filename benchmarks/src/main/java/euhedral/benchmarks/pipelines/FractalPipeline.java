package euhedral.benchmarks.pipelines;

import euhedral.benchmarks.frames.FractalFrame;
import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.AbstractExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.config.DRRConfig;
import euhedral.io.config.ExecutionManagerConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.impl.DefaultCloneablePipeline;
import euhedral.io.interfaces.PipelineExecutor;
import org.openjdk.jmh.infra.Blackhole;

public class FractalPipeline extends DefaultCloneablePipeline {

    private final Blackhole blackhole;

    public FractalPipeline(String name, DRRConfig drrConfig, ExecutionManagerConfig emConfig, Blackhole blackhole) {
        super(name, drrConfig, emConfig, new FractalExecutor(null, blackhole));
        this.blackhole = blackhole;
    }

    private FractalPipeline(String name, DRRConfig drrConfig, ExecutionManagerConfig emConfig,
            PipelineExecutor executor, Blackhole blackhole) {
        super(name, drrConfig, emConfig, executor);
        this.blackhole = blackhole;
    }

    private static class FractalExecutor extends AbstractExecutor {
        private final Blackhole blackhole;

        public FractalExecutor(PinnedThreadExecutor executor, Blackhole blackhole) {
            super(executor);
            this.blackhole = blackhole;
        }

        @Override
        public void execute(AbstractFrame frame) {
            if(frame instanceof FractalFrame fractal) {
                int escape = fractal.compute();
                blackhole.consume(escape);
                blackhole.consume(fractal);
                fractal.counters.getAndAdd(this.executorService.getCpu(), 4);
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
