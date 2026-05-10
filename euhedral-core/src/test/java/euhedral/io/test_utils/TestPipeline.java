package euhedral.io.test_utils;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.AbstractCloneablePipeline;
import euhedral.io.AbstractExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.CacheManager;
import euhedral.io.interfaces.PipelineExecutor;
import euhedral.io.interfaces.SlotManager;
import org.openjdk.jmh.infra.Blackhole;

public class TestPipeline extends AbstractCloneablePipeline {

    private final String name;

    public TestPipeline(String name, CloneConfig config, CacheManager cacheManager,
            SlotManager slotManager, PipelineExecutor executor) {
        super(name, config, cacheManager, slotManager, executor);
        this.name = name;
    }

    @Override
    public TestPipeline hookOnClone(CloneConfig cloneConfig) {
        return new TestPipeline(name, cloneConfig, this.cacheManager, this.slotManager,
                this.executor);
    }

    public static class TestExecutor extends AbstractExecutor {

        private final PinnedThreadExecutor executor;
        private final Blackhole bh;

        public TestExecutor(PinnedThreadExecutor executor, Blackhole bh) {
            super(executor);
            this.executor = executor;
            this.bh = bh;
        }

        @Override
        public void execute(AbstractFrame frame) {
            if(bh != null) {
                bh.consume(frame);
            }
        }

        @Override
        public AbstractExecutor clone(CloneConfig cloneConfig) {
            return new TestExecutor(this.executor, this.bh);
        }

        @Override
        public AbstractExecutor clone(CloneConfig cloneConfig,
                PinnedThreadExecutor executor) {
            return new TestExecutor(executor, this.bh);
        }
    }
}
