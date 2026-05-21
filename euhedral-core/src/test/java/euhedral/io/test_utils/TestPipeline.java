package euhedral.io.test_utils;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.generics.AbstractCloneablePipeline;
import euhedral.io.generics.AbstractExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.CacheManager;
import euhedral.io.generics.PipelineExecutor;
import euhedral.io.generics.SlotManager;
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
        SlotManager manager = this.slotManager.clone(cloneConfig);
        return new TestPipeline(name, cloneConfig, this.cacheManager.clone(cloneConfig), manager,
                this.executor.clone(cloneConfig, manager.getPinnedExecutor()));
    }

    public static class TestExecutor extends AbstractExecutor {

        private final Blackhole bh;

        public TestExecutor(PinnedThreadExecutor executor, Blackhole bh) {
            super(executor);
            this.bh = bh;
        }

        @Override
        public void execute(AbstractFrame frame) {
            if(bh != null) {
                bh.consume(frame);
            }
            ((TestFrame) frame).counters.increment(this.executorService.getCpu());
        }

        @Override
        public AbstractExecutor clone(CloneConfig cloneConfig) {
            return new TestExecutor(this.executorService, this.bh);
        }

        @Override
        public AbstractExecutor clone(CloneConfig cloneConfig,
                PinnedThreadExecutor executor) {
            return new TestExecutor(executor, this.bh);
        }
    }
}
