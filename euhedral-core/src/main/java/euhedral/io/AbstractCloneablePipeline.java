package euhedral.io;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.control_plane.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.interfaces.CacheManager;
import euhedral.io.interfaces.PipelineExecutor;
import euhedral.io.interfaces.SlotManager;
import euhedral.hardware_utils.common.SystemUtilization.CoreSnapshot;

import java.util.concurrent.Future;
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

    public AbstractCloneablePipeline(String name, CloneConfig cloneConfig,
            CacheManager cacheManager,
            SlotManager slotManager,
            PipelineExecutor executor) {
        this.logger = LoggerFactory.getLogger(name);
        this.config = cloneConfig;
        this.name = name;
        this.cacheManager = cacheManager.clone(cloneConfig);
        this.slotManager = slotManager.clone(cloneConfig);
        this.executor = executor.clone(cloneConfig, slotManager.getPinnedExecutor());

        cacheManager.setDownstreamPressureMonitor(slotManager::getPressure);
    }

    @Override
    public void start() {
        executor.start();
        slotManager.start();
        cacheManager.start();

        executor.reportErrorsTo(slotManager);
        executor.ingest(slotManager.output());
        slotManager.ingest(cacheManager.output());
    }

    @Override
    public boolean isStarted() {
        return cacheManager.isStarted() && slotManager.isStarted() && executor.isStarted();
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        cacheManager.update(snapshot);
        slotManager.update(snapshot);
        executor.update(snapshot);
    }

    @Override
    public Publisher<? extends AbstractFrame> process(Publisher<? extends AbstractFrame> frameFlux) {
        ingest(frameFlux);
        return output();
    }

    @Override
    public void ingest(Publisher<? extends AbstractFrame> frameFlux) {
        cacheManager.ingest(frameFlux);
    }

    @Override
    public Publisher<? extends AbstractFrame> output() {
        return executor.output();
    }

    @Override
    public double getPressure() {
        return slotManager.getPressure();
    }

    @Override
    public boolean isDrained() {
        return cacheManager.isDrained() &&
                slotManager.isDrained() &&
                executor.isDrained();
    }

    @Override
    public void setDrainMode(boolean value) {
        if (value) {
            executor.setDrainMode(value);
            slotManager.setDrainMode(value);
            cacheManager.setDrainMode(value);
        } else {
            cacheManager.setDrainMode(value);
            slotManager.setDrainMode(value);
            executor.setDrainMode(value);
        }
    }

    @Override
    public int getCore() {
        return config == null ? -1 : config.coreId();
    }

    @Override
    public void dumpLocks() {
        cacheManager.dumpLocks();
        slotManager.dumpLocks();
        executor.dumpLocks();
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
            throw new RuntimeException("Failed to allocate the AbstractCloneablePipeline implementation.", t);
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
                slotManager.close();
            } catch (Exception e) {
                logger.error("Failed to close {}", slotManager.getClass(), e);
            }
            try {
                executor.close();
            } catch (Exception e) {
                logger.error("Failed to close {}", executor.getClass(), e);
            }
            try {
                cacheManager.close();
            } catch (Exception e) {
                logger.error("Failed to close {}", cacheManager.getClass(), e);
            }
        } catch (Exception e) {
            logger.error("Failed to close pipeline properly", e);
        }
    }
}
