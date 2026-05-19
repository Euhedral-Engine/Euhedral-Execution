package euhedral.io.impl;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.AbstractExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.config.DRRConfig;
import euhedral.io.config.ExecutionManagerConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.ConsumerFrame;
import euhedral.io.frames.FunctionFrame;
import euhedral.io.frames.RunnableFrame;
import euhedral.io.frames.SequencedFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FunctionalPipeline extends DefaultCloneablePipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(FunctionalPipeline.class);

    public FunctionalPipeline(String name, DRRConfig drrConfig,
            ExecutionManagerConfig emConfig) {
        super(name, drrConfig, emConfig, new FunctionalExecutor(null));
    }

    private static class FunctionalExecutor extends AbstractExecutor {

        FunctionalExecutor(PinnedThreadExecutor executorService) {
            super(executorService);
        }

        @Override
        public void execute(AbstractFrame frame) {
            if (!frame.isAlive()) {
                frame.throwMeAsError();
            }

            if (frame instanceof SequencedFrame<?, ?> s) {
                s.apply();
            } else if (frame instanceof ConsumerFrame<?> c) {
                c.consume();
            } else if (frame instanceof FunctionFrame<?, ?> f) {
                f.apply();
            } else if (frame instanceof RunnableFrame r) {
                r.run();
            } else {
                LOGGER.error("Unhandled frame type {}", frame.getClass());
                frame.throwMeAsError();
            }
        }

        @Override
        public FunctionalExecutor clone(CloneConfig cloneConfig) {
            return new FunctionalExecutor(executorService);
        }

        @Override
        public FunctionalExecutor clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
            return new FunctionalExecutor(executor);
        }
    }
}
