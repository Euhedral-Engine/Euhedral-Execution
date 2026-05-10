package euhedral.io.impl;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.AbstractCloneablePipeline;
import euhedral.io.AbstractExecutor;
import euhedral.io.DRRScheduler;
import euhedral.io.ExecutionManager;
import euhedral.io.control_plane.CloneConfig;
import euhedral.io.interfaces.CacheManager;
import euhedral.io.interfaces.PipelineExecutor;
import euhedral.io.interfaces.SlotManager;
import io.micrometer.core.instrument.MeterRegistry;
import org.jspecify.annotations.Nullable;

public class DefaultCloneablePipeline extends AbstractCloneablePipeline {

    public DefaultCloneablePipeline(String name, String metricPrefix,
            @Nullable MeterRegistry meterRegistry, AbstractExecutor executor) {
        this(name,
                new DRRScheduler.Config(null, metricPrefix, meterRegistry),
                ExecutionManager.Config.lowLatencyDefault(meterRegistry, metricPrefix),
                executor);
    }

    public DefaultCloneablePipeline(String name, DRRScheduler.Config drrConfig,
            ExecutionManager.Config dsmConfig, AbstractExecutor executor) {
        super(name, null, getDrrScheduler(drrConfig), getSlotManager(dsmConfig), executor);
    }

    private DefaultCloneablePipeline(String name, CloneConfig config, CacheManager scheduler,
            SlotManager slotManager, PipelineExecutor executor) {
        super(name, config, scheduler, slotManager, executor);
    }

    private static DRRScheduler getDrrScheduler(DRRScheduler.Config drrConfig) {
        return new DRRScheduler(drrConfig, null);
    }

    private static ExecutionManager getSlotManager(ExecutionManager.Config dsmConfig) {
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
        return new DefaultCloneablePipeline(super.name, cloneConfig,
                super.cacheManager.clone(cloneConfig),
                super.slotManager.clone(cloneConfig),
                super.executor.clone(cloneConfig));
    }
}
