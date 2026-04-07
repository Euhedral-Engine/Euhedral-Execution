package euhedral.io;

import euhedral.io.control_plane.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.interfaces.DispatchPreProcess;
import euhedral.io.interfaces.SlotManager;
import euhedral.io.utils.SystemUtilization.CoreSnapshot;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractCloneablePipeline implements
        CloneableObject {

    protected final Logger logger;
    protected final CloneConfig config;
    protected final String name;

    protected final DispatchPreProcess preProcess;
    protected final SlotManager slotManager;
    protected final AbstractExecutor executor;

    public AbstractCloneablePipeline(String name, CloneConfig cloneConfig,
            DispatchPreProcess preProcess,
            SlotManager slotManager,
            AbstractExecutor executor) {
        this.logger = LoggerFactory.getLogger(name);
        this.config = cloneConfig;
        this.name = name;
        this.preProcess = preProcess.clone(cloneConfig);
        this.slotManager = slotManager.clone(cloneConfig);
        this.executor = executor.clone(cloneConfig, slotManager.getPinnedExecutor());
        init();
    }

    private void init() {
        preProcess.setDownstreamPressureMonitor(slotManager::getPressure);
    }

    @Override
    public void start() {
        executor.start();
        slotManager.start();
        preProcess.start();

        executor.reportErrorsTo(slotManager);
        executor.ingest(slotManager.output());
        slotManager.ingest(preProcess.output());
    }

    @Override
    public boolean isStarted() {
        return preProcess.isStarted() && slotManager.isStarted() && executor.isStarted();
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        preProcess.update(snapshot);
        slotManager.update(snapshot);
        executor.update(snapshot);
    }

    @Override
    public Publisher<AbstractFrame> process(Publisher<AbstractFrame> frameFlux) {
        ingest(frameFlux);
        return output();
    }

    @Override
    public void ingest(Publisher<AbstractFrame> frameFlux) {
        preProcess.ingest(frameFlux);
    }

    @Override
    public Publisher<AbstractFrame> output() {
        return executor.output();
    }

    @Override
    public double getPressure() {
        return slotManager.getPressure();
    }

    @Override
    public boolean isDrained() {
        return preProcess.isDrained() &&
                slotManager.isDrained() &&
                executor.isDrained();
    }

    @Override
    public void setDrainMode(boolean value) {
        if (value) {
            executor.setDrainMode(value);
            slotManager.setDrainMode(value);
            preProcess.setDrainMode(value);
        } else {
            preProcess.setDrainMode(value);
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
        preProcess.dumpLocks();
        slotManager.dumpLocks();
        executor.dumpLocks();
    }

    @Override
    public abstract AbstractCloneablePipeline clone(CloneConfig cloneConfig);

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
                preProcess.close();
            } catch (Exception e) {
                logger.error("Failed to close {}", preProcess.getClass(), e);
            }
        } catch (Exception e) {
            logger.error("Failed to close pipeline properly", e);
        }
    }
}
