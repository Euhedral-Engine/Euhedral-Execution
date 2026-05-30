package euhedral.io.impl;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;
import euhedral.io.config.DRRConfig;
import euhedral.io.config.SchedulingConfig;
import euhedral.io.control_plane.ControlPlaneFragment;
import euhedral.io.control_plane.DRRCacheManager;
import euhedral.io.generics.AbstractCloneablePipeline;
import euhedral.io.generics.CacheManager;
import euhedral.io.generics.PipelineExecutor;
import euhedral.io.generics.SlotManager;
import io.micrometer.core.instrument.MeterRegistry;

/// The minimal implementation of an [AbstractCloneablePipeline]
public class DefaultCloneablePipeline extends AbstractCloneablePipeline {

    private static DRRCacheManager getDrrScheduler(DRRConfig drrConfig) {
        return new DRRCacheManager(drrConfig);
    }

    private static ControlPlaneFragment getSlotManager(SchedulingConfig dsmConfig) {
        return new ControlPlaneFragment(dsmConfig);
    }

    public DefaultCloneablePipeline() {
        this(DRRConfig.defaultConfig(null, null),
                SchedulingConfig.balancedDefault(null, null), new DefaultExecutor(null));
    }

    public DefaultCloneablePipeline(String metricPrefix,
            MeterRegistry meterRegistry) {
        this(DRRConfig.defaultConfig(metricPrefix, meterRegistry),
                SchedulingConfig.balancedDefault(meterRegistry, metricPrefix),
                new DefaultExecutor(null));
    }

    public DefaultCloneablePipeline(String metricPrefix,
            MeterRegistry meterRegistry, PipelineExecutor executor) {
        this(
                DRRConfig.defaultConfig(metricPrefix, meterRegistry),
                SchedulingConfig.balancedDefault(meterRegistry, metricPrefix),
                executor);
    }

    public DefaultCloneablePipeline(DRRConfig drrConfig,
            SchedulingConfig schedulingConfig) {
        super(null, getDrrScheduler(drrConfig), getSlotManager(schedulingConfig),
                new DefaultExecutor(null));
    }

    public DefaultCloneablePipeline(DRRConfig drrConfig,
            SchedulingConfig schedulingConfig, PipelineExecutor executor) {
        super(null, getDrrScheduler(drrConfig), getSlotManager(schedulingConfig), executor);
    }

    private DefaultCloneablePipeline(CloneConfig config, CacheManager cacheManager,
            SlotManager slotManager, PipelineExecutor executor) {
        super(config, cacheManager, slotManager, executor);
    }

    @Override
    public final DefaultCloneablePipeline clone(CloneConfig cloneConfig,
            PinnedThreadExecutor executor) {
        return new DefaultCloneablePipeline(cloneConfig,
                super.cacheManager.clone(cloneConfig, executor),
                super.slotManager.clone(cloneConfig, executor),
                super.executor.clone(cloneConfig, executor));
    }

    @Override
    public final AbstractCloneablePipeline hookOnClone(CloneConfig cloneConfig) {
        CacheManager cManager = super.cacheManager.clone(cloneConfig);
        SlotManager sManager = super.slotManager.clone(cloneConfig);
        return new DefaultCloneablePipeline(cloneConfig, cManager, sManager,
                super.executor.clone(cloneConfig, sManager.getPinnedExecutor()));
    }
}
