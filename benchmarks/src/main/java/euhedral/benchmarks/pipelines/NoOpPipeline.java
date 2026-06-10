package euhedral.benchmarks.pipelines;

import euhedral.benchmarks.frames.NoOpFrame;
import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CacheConfig;
import euhedral.io.config.CloneConfig;
import euhedral.io.config.FragmentConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.AbstractExecutor;
import euhedral.io.impl.DefaultCloneablePipeline;
import org.openjdk.jmh.infra.Blackhole;

public class NoOpPipeline extends DefaultCloneablePipeline {

    public NoOpPipeline(CacheConfig cacheConfig, FragmentConfig emConfig, Blackhole blackhole) {
        super(cacheConfig, emConfig, new NoOpExecutor(null, blackhole));
    }

    private static class NoOpExecutor extends AbstractExecutor {
        private final Blackhole blackhole;

        public NoOpExecutor(PinnedThreadExecutor executor, Blackhole blackhole) {
            super(executor);
            this.blackhole = blackhole;
        }

        @Override
        public void execute(AbstractFrame frame) {
            if(frame instanceof NoOpFrame noOp) {
                noOp.cpu = this.executorService.getCpu();
            }
            blackhole.consume(frame);
        }

        @Override
        public NoOpExecutor clone(CloneConfig cloneConfig) {
            return new NoOpExecutor(super.executorService, this.blackhole);
        }

        @Override
        public NoOpExecutor clone(CloneConfig cloneConfig,
                PinnedThreadExecutor executor) {
            return new NoOpExecutor(executor, this.blackhole);
        }
    }
}
