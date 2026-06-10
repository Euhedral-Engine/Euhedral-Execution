package euhedral.io.impl;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CacheConfig;
import euhedral.io.config.CloneConfig;
import euhedral.io.config.FragmentConfig;
import euhedral.io.control_plane.ControlPlaneFragment;
import euhedral.io.generics.AbstractCloneablePipeline;
import euhedral.io.generics.PipelineExecutor;
import euhedral.io.generics.SlotManager;
import io.micrometer.core.instrument.MeterRegistry;

/// The minimal implementation of an [AbstractCloneablePipeline]
@SuppressWarnings("unused")
public class DefaultCloneablePipeline extends AbstractCloneablePipeline {

    private static ControlPlaneFragment getFragment(CacheConfig cacheConfig, FragmentConfig dsmConfig) {
        return new ControlPlaneFragment(cacheConfig, dsmConfig);
    }

    public DefaultCloneablePipeline() {
        this(CacheConfig.defaultConfig(null, null),
                FragmentConfig.balancedDefault(null, null), new DefaultExecutor(null));
    }

    public DefaultCloneablePipeline(String metricPrefix,
            MeterRegistry meterRegistry) {
        this(CacheConfig.defaultConfig(metricPrefix, meterRegistry),
                FragmentConfig.balancedDefault(meterRegistry, metricPrefix),
                new DefaultExecutor(null));
    }

    public DefaultCloneablePipeline(String metricPrefix,
            MeterRegistry meterRegistry, PipelineExecutor executor) {
        this(
                CacheConfig.defaultConfig(metricPrefix, meterRegistry),
                FragmentConfig.balancedDefault(meterRegistry, metricPrefix),
                executor);
    }

    public DefaultCloneablePipeline(CacheConfig cacheConfig,
            FragmentConfig fragmentConfig) {
        super(null, getFragment(cacheConfig, fragmentConfig),
                new DefaultExecutor(null));
    }

    public DefaultCloneablePipeline(CacheConfig cacheConfig,
            FragmentConfig fragmentConfig, PipelineExecutor executor) {
        super(null, getFragment(cacheConfig, fragmentConfig), executor);
    }

    private DefaultCloneablePipeline(CloneConfig config,
            SlotManager slotManager, PipelineExecutor executor) {
        super(config, slotManager, executor);
    }

    @Override
    public final DefaultCloneablePipeline clone(CloneConfig cloneConfig,
            PinnedThreadExecutor executor) {
        return new DefaultCloneablePipeline(cloneConfig,
                super.slotManager.clone(cloneConfig, executor),
                super.executor.clone(cloneConfig, executor));
    }

    @Override
    public final AbstractCloneablePipeline hookOnClone(CloneConfig cloneConfig) {
        SlotManager sManager = super.slotManager.clone(cloneConfig);
        return new DefaultCloneablePipeline(cloneConfig, sManager,
                super.executor.clone(cloneConfig, sManager.getPinnedExecutor()));
    }
}
