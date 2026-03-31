package euhedral.common.io.test_utils;

import euhedral.common.io.dispatch.AbstractCloneablePipeline;
import euhedral.common.io.dispatch.AbstractExecutor;
import euhedral.common.io.dispatch.control_plane.CloneConfig;
import euhedral.common.io.dispatch.frames.AbstractFrame;
import euhedral.common.io.dispatch.interfaces.DispatchPreProcess;
import euhedral.common.io.dispatch.interfaces.SlotManager;
import euhedral.common.io.dispatch.utils.PinnedThreadExecutor;
import org.openjdk.jmh.infra.Blackhole;

public class TestPipeline extends AbstractCloneablePipeline {

    private final String name;

    public TestPipeline(String name, CloneConfig config, DispatchPreProcess scheduler,
            SlotManager slotManager, AbstractExecutor executor) {
        super(name, config, scheduler, slotManager, executor);
        this.name = name;
    }

    @Override
    public TestPipeline clone(CloneConfig cloneConfig) {
        return new TestPipeline(name, cloneConfig, this.preProcess, this.slotManager,
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
            bh.consume(frame);
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
