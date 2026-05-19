package euhedral.io.impl;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.AbstractCloneablePipeline;
import euhedral.io.AbstractExecutor;
import euhedral.io.DRRCacheManager;
import euhedral.io.ExecutionManager;
import euhedral.io.config.CloneConfig;
import euhedral.io.config.DRRConfig;
import euhedral.io.config.ExecutionManagerConfig;
import euhedral.io.interfaces.CacheManager;
import euhedral.io.interfaces.PipelineExecutor;
import euhedral.io.interfaces.SlotManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;

public class DefaultCloneablePipeline extends AbstractCloneablePipeline {

    public DefaultCloneablePipeline(String name, String metricPrefix,
            @Nullable MeterRegistry meterRegistry, AbstractExecutor executor) {
        this(name,
                new DRRConfig(null, metricPrefix, meterRegistry),
                ExecutionManagerConfig.balancedDefault(meterRegistry, metricPrefix),
                executor);
    }

    public DefaultCloneablePipeline(String name, DRRConfig drrConfig,
            ExecutionManagerConfig dsmConfig, PipelineExecutor executor) {
        super(name, null, getDrrScheduler(drrConfig), getSlotManager(dsmConfig), executor);
    }

    private DefaultCloneablePipeline(String name, CloneConfig config, CacheManager scheduler,
            SlotManager slotManager, PipelineExecutor executor) {
        super(name, config, scheduler, slotManager, executor);
    }

    private static DRRCacheManager getDrrScheduler(DRRConfig drrConfig) {
        return new DRRCacheManager(drrConfig);
    }

    private static ExecutionManager getSlotManager(ExecutionManagerConfig dsmConfig) {
        return new ExecutionManager(dsmConfig);
    }

    @Override
    public final DefaultCloneablePipeline clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
        return new DefaultCloneablePipeline(super.name, cloneConfig,
                super.cacheManager.clone(cloneConfig, executor),
                super.slotManager.clone(cloneConfig, executor),
                super.executor.clone(cloneConfig, executor));
    }

    @Override
    public final AbstractCloneablePipeline hookOnClone(CloneConfig cloneConfig) {
        CacheManager cManager = super.cacheManager.clone(cloneConfig);
        SlotManager sManager = super.slotManager.clone(cloneConfig);
        return new DefaultCloneablePipeline(super.name, cloneConfig,
                cManager, sManager,
                super.executor.clone(cloneConfig, sManager.getPinnedExecutor()));
    }
}
