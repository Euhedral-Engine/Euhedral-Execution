package euhedral.io;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.common.SystemUtilization.CoreSnapshot;
import euhedral.io.config.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.CacheManager;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.interfaces.PipelineExecutor;
import euhedral.io.interfaces.SlotManager;
import java.util.concurrent.Future;

import org.jspecify.annotations.Nullable;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractCloneablePipeline implements
        CloneableObject {

    protected final Logger logger;
    protected final CloneConfig config;
    protected final String name;

    protected final CacheManager cacheManager;
    protected final SlotManager slotManager;
    protected final PipelineExecutor executor;

    public AbstractCloneablePipeline(String name, @Nullable CloneConfig cloneConfig,
            CacheManager cacheManager,
            SlotManager slotManager,
            PipelineExecutor executor) {
        this.logger = LoggerFactory.getLogger(name);
        this.config = cloneConfig;
        this.name = name;
        if(cloneConfig == null) {
            this.cacheManager = cacheManager;
            this.slotManager = slotManager;
            this.executor = executor;
        } else {
            this.cacheManager = cacheManager.clone(cloneConfig);
            this.slotManager = slotManager.clone(cloneConfig);
            this.executor = executor.clone(cloneConfig, slotManager.getPinnedExecutor());
        }
    }

    @Override
    public void start() {
        this.executor.start();
        this.slotManager.start();
        this.cacheManager.start();

        this.executor.reportErrorsTo(this.slotManager);
        this.executor.ingest(this.slotManager.output());
        this.slotManager.ingest(this.cacheManager.output());
    }

    @Override
    public boolean isStarted() {
        return this.cacheManager.isStarted() && this.slotManager.isStarted() && this.executor.isStarted();
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        this.cacheManager.update(snapshot);
        this.slotManager.update(snapshot);
        this.executor.update(snapshot);
    }

    @Override
    public Publisher<? extends AbstractFrame> process(Publisher<? extends AbstractFrame> frameFlux) {
        ingest(frameFlux);
        return output();
    }

    @Override
    public void ingest(Publisher<? extends AbstractFrame> frameFlux) {
        this.cacheManager.ingest(frameFlux);
    }

    @Override
    public Publisher<? extends AbstractFrame> output() {
        return this.executor.output();
    }

    @Override
    public double getPressure() {
        return this.slotManager.getPressure();
    }

    @Override
    public boolean isDrained() {
        return this.cacheManager.isDrained() &&
                this.slotManager.isDrained() &&
                this.executor.isDrained();
    }

    @Override
    public void setDrainMode(boolean value) {
        if (value) {
            this.executor.setDrainMode(value);
            this.slotManager.setDrainMode(value);
            this.cacheManager.setDrainMode(value);
        } else {
            this.cacheManager.setDrainMode(value);
            this.slotManager.setDrainMode(value);
            this.executor.setDrainMode(value);
        }
    }

    @Override
    public int getCore() {
        return this.config == null ? -1 : this.config.coreId();
    }

    @Override
    public void dumpLocks() {
        this.cacheManager.dumpLocks();
        this.slotManager.dumpLocks();
        this.executor.dumpLocks();
    }

    @Override
    public final AbstractCloneablePipeline clone(CloneConfig cloneConfig) {
        int cpu = cloneConfig.effectiveCpus().nextSetBit(0);

        boolean createdExecutor = false;
        PinnedThreadExecutor executor = PinnedThreadExecutor.get(cpu);

        if(executor == null) {
            executor = PinnedThreadExecutor.getOrSetIfAbsent(cpu, cloneConfig.shardName() + "-" + AbstractCloneablePipeline.class, Thread.MAX_PRIORITY, true);
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
            throw new RuntimeException("Failed to construct the AbstractCloneablePipeline implementation.", t);
        }

        if(createdExecutor) {
            executor.shutdownNow();
        }
        return retVal;
    }

    public abstract AbstractCloneablePipeline hookOnClone(CloneConfig cloneConfig);

    @Override
    public void close() throws Exception {
        try {
            try {
                this.slotManager.close();
            } catch (Exception e) {
                this.logger.error("Failed to close {}", this.slotManager.getClass(), e);
            }
            try {
                this.executor.close();
            } catch (Exception e) {
                this.logger.error("Failed to close {}", this.executor.getClass(), e);
            }
            try {
                this.cacheManager.close();
            } catch (Exception e) {
                this.logger.error("Failed to close {}", this.cacheManager.getClass(), e);
            }
        } catch (Exception e) {
            this.logger.error("Failed to close pipeline properly", e);
        }
    }
}
