package euhedral.io.impl;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CacheConfig;
import euhedral.io.config.CloneConfig;
import euhedral.io.config.SchedulingConfig;
import euhedral.io.control_plane.ControlPlaneCache;
import euhedral.io.control_plane.ControlPlaneFragment;
import euhedral.io.generics.AbstractCloneablePipeline;
import euhedral.io.generics.CacheManager;
import euhedral.io.generics.PipelineExecutor;
import euhedral.io.generics.SlotManager;
import io.micrometer.core.instrument.MeterRegistry;

/// The minimal implementation of an [AbstractCloneablePipeline]
public class DefaultCloneablePipeline extends AbstractCloneablePipeline {

    private static ControlPlaneCache getCache(CacheConfig cacheConfig) {
        return new ControlPlaneCache(cacheConfig);
    }

    private static ControlPlaneFragment getFragment(SchedulingConfig dsmConfig) {
        return new ControlPlaneFragment(dsmConfig);
    }

    public DefaultCloneablePipeline() {
        this(CacheConfig.defaultConfig(null, null),
                SchedulingConfig.balancedDefault(null, null), new DefaultExecutor(null));
    }

    public DefaultCloneablePipeline(String metricPrefix,
            MeterRegistry meterRegistry) {
        this(CacheConfig.defaultConfig(metricPrefix, meterRegistry),
                SchedulingConfig.balancedDefault(meterRegistry, metricPrefix),
                new DefaultExecutor(null));
    }

    public DefaultCloneablePipeline(String metricPrefix,
            MeterRegistry meterRegistry, PipelineExecutor executor) {
        this(
                CacheConfig.defaultConfig(metricPrefix, meterRegistry),
                SchedulingConfig.balancedDefault(meterRegistry, metricPrefix),
                executor);
    }

    public DefaultCloneablePipeline(CacheConfig cacheConfig,
            SchedulingConfig schedulingConfig) {
        super(null, getCache(cacheConfig), getFragment(schedulingConfig),
                new DefaultExecutor(null));
    }

    public DefaultCloneablePipeline(CacheConfig cacheConfig,
            SchedulingConfig schedulingConfig, PipelineExecutor executor) {
        super(null, getCache(cacheConfig), getFragment(schedulingConfig), executor);
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
