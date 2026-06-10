package euhedral.io.generics;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.common.SystemUtilization.CoreSnapshot;
import euhedral.io.config.CloneConfig;
import java.util.concurrent.Future;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The base implementation of a CloneableObject
///
/// This class is responsible for connecting a [SlotManager] to a [PipelineExecutor]. It also
/// automatically broadcasts lifecycle updates to the instances.
public abstract class AbstractCloneablePipeline implements CloneableObject {

    protected final Logger logger;
    protected final CloneConfig config;

    protected final SlotManager slotManager;
    protected final PipelineExecutor executor;

    public AbstractCloneablePipeline(@Nullable CloneConfig cloneConfig,
            @NonNull SlotManager slotManager,
            @NonNull PipelineExecutor executor) {
        if (cloneConfig != null) {
            this.logger = LoggerFactory.getLogger(
                    cloneConfig.shardName() + "-pipeline-" + cloneConfig.coreId());
        } else {
            this.logger = LoggerFactory.getLogger(this.getClass().getSimpleName());
        }
        this.config = cloneConfig;
        this.slotManager = slotManager;
        this.executor = executor;
    }

    @Override
    public void start() {
        this.executor.start();
        this.slotManager.start();

        this.executor.reportCompletionsTo(this.slotManager);
        this.executor.input(this.slotManager.output());
    }

    @Override
    public boolean isStarted() {
        if (this.slotManager != null && !this.slotManager.isStarted()) {
            return false;
        }
        return this.executor == null || this.executor.isStarted();
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        if (this.slotManager != null) {
            this.slotManager.update(snapshot);
        }
        if (this.executor != null) {
            this.executor.update(snapshot);
        }
    }

    @Override
    public void input(LatticeSource stream) {
        this.slotManager.input(stream);
    }

    @Override
    public LatticeSource output() {
        return this.executor.output();
    }

    @Override
    public boolean isDrained() {
        if (this.slotManager != null && !this.slotManager.isDrained()) {
            return false;
        }
        return this.executor == null || this.executor.isDrained();
    }

    @Override
    public void setDrainMode(boolean value) {
        if (this.executor != null) {
            this.executor.setDrainMode(value);
        }
        if (this.slotManager != null) {
            this.slotManager.setDrainMode(value);
        }
    }

    @Override
    public final int getCore() {
        return this.config == null ? -1 : this.config.coreId();
    }

    @Override
    public void dumpLocks() {
        if (this.slotManager != null) {
            this.slotManager.dumpLocks();
        }
        if (this.executor != null) {
            this.executor.dumpLocks();
        }
    }

    @Override
    public final AbstractCloneablePipeline clone(CloneConfig cloneConfig) {
        int cpu = cloneConfig.effectiveCpus().nextSetBit(0);

        boolean createdExecutor = false;
        PinnedThreadExecutor executor = PinnedThreadExecutor.get(cpu);

        if (executor == null) {
            executor = PinnedThreadExecutor.getOrSetIfAbsent(cpu,
                    cloneConfig.shardName() + "-" + AbstractCloneablePipeline.class,
                    Thread.MAX_PRIORITY, true);
            createdExecutor = true;
        }

        Future<AbstractCloneablePipeline> allocated = executor.submit(() -> {
            AbstractCloneablePipeline pipeline = hookOnClone(cloneConfig);
            pipeline.firstTouch();
            return pipeline;
        });
        AbstractCloneablePipeline retVal;

        try {
            retVal = allocated.get();
        } catch (Throwable t) {
            throw new RuntimeException(
                    "Failed to construct the AbstractCloneablePipeline implementation.", t);
        }

        if (createdExecutor) {
            executor.shutdownNow();
        }
        return retVal;
    }

    public abstract AbstractCloneablePipeline hookOnClone(CloneConfig cloneConfig);

    @Override
    public void close() throws Exception {
        try {
            try {
                if (this.slotManager != null) {
                    this.slotManager.close();
                }
            } catch (Exception e) {
                this.logger.error("Failed to close {}", this.slotManager.getClass(), e);
            }
            try {
                if (this.executor != null) {
                    this.executor.close();
                }
            } catch (Exception e) {
                this.logger.error("Failed to close {}", this.executor.getClass(), e);
            }
        } catch (Exception e) {
            this.logger.error("Failed to close pipeline properly", e);
        }
    }
}
