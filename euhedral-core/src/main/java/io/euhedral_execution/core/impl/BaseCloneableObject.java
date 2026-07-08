package io.euhedral_execution.core.impl;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneFragment;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.core.generics.CloneableObject;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The base implementation of a CloneableObject
///
/// This class is responsible for connecting a [ControlPlaneFragment] to a [PipelineExecutor]. It
/// also automatically broadcasts lifecycle updates to the instances.
@SuppressWarnings("unused")
public final class BaseCloneableObject implements CloneableObject {

    private final Logger logger;
    private final CloneConfig config;

    private final ControlPlaneFragment fragment;
    private final AbstractExecutor executor;

    public BaseCloneableObject() {
        this(FragmentConfig.ofDefaults(), new DefaultExecutor(-1));
    }

    public BaseCloneableObject(String metricPrefix,
            MeterRegistry meterRegistry) {
        this(FragmentConfig.ofDefaults(metricPrefix, meterRegistry), new DefaultExecutor(-1));
    }

    public BaseCloneableObject(AbstractExecutor executor) {
        this(FragmentConfig.ofDefaults(), executor);
    }

    public BaseCloneableObject(String metricPrefix,
            MeterRegistry meterRegistry, AbstractExecutor executor) {
        this(FragmentConfig.ofDefaults(metricPrefix, meterRegistry), executor);
    }

    public BaseCloneableObject(FragmentConfig fragmentConfig) {
        this(null, new ControlPlaneFragment(fragmentConfig), new DefaultExecutor(-1));
    }

    public BaseCloneableObject(FragmentConfig fragmentConfig, AbstractExecutor executor) {
        this(null, new ControlPlaneFragment(fragmentConfig), executor);
    }

    private BaseCloneableObject(CloneConfig config,
            ControlPlaneFragment fragment, AbstractExecutor executor) {
        if (config != null) {
            this.logger = LoggerFactory.getLogger(
                    config.shardName() + "-pipeline-" + config.coreId());
        } else {
            this.logger = LoggerFactory.getLogger(this.getClass().getSimpleName());
        }
        this.config = config;
        this.fragment = fragment;
        this.executor = executor;
    }

    @Override
    public void start() {
        this.executor.start();
        this.fragment.start();
        this.executor.input(this.fragment.output());
    }

    @Override
    public boolean isStarted() {
        if (this.fragment != null && !this.fragment.isStarted()) {
            return false;
        }
        return this.executor == null || this.executor.isStarted();
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        if (this.fragment != null) {
            this.fragment.update(snapshot);
        }
        if (this.executor != null) {
            this.executor.update(snapshot);
        }
    }

    @Override
    public void input(LatticeSource stream) {
        this.fragment.input(stream);
    }

    @Override
    public LatticeSource output() {
        return this.executor.output();
    }

    @Override
    public boolean isDrained() {
        if (this.fragment != null && !this.fragment.isDrained()) {
            return false;
        }
        return this.executor == null || this.executor.isDrained();
    }

    @Override
    public void setDrainMode(boolean value) {
        if (this.executor != null) {
            this.executor.setDrainMode(value);
        }
        if (this.fragment != null) {
            this.fragment.setDrainMode(value);
        }
    }

    @Override
    public int getCore() {
        return this.config == null ? -1 : this.config.coreId();
    }

    @Override
    public void dumpLocks() {
        if (this.fragment != null) {
            this.fragment.dumpLocks();
        }
        if (this.executor != null) {
            this.executor.dumpLocks();
        }
    }

    @Override
    public BaseCloneableObject clone(CloneConfig cloneConfig,
            PinnedThreadExecutor executor) {
        return new BaseCloneableObject(cloneConfig,
                (ControlPlaneFragment) this.fragment.clone(cloneConfig, executor),
                this.executor.clone(cloneConfig));
    }

    @Override
    public BaseCloneableObject clone(CloneConfig cloneConfig) {
        int cpu = cloneConfig.effectiveCpus().nextSetBit(0);

        boolean createdExecutor = false;
        PinnedThreadExecutor executor = PinnedThreadExecutor.get(cpu);

        if (executor == null) {
            executor = PinnedThreadExecutor.getOrSetIfAbsent(FlowThread.getFactory(), cpu,
                    cloneConfig.shardName() + "-" + BaseCloneableObject.class,
                    Thread.MAX_PRIORITY, true);
            createdExecutor = true;
        }

        Future<BaseCloneableObject> allocated = executor.submit(() -> {
            ControlPlaneFragment fragment = this.fragment.clone(cloneConfig);
            BaseCloneableObject pipeline = new BaseCloneableObject(cloneConfig, fragment,
                    this.executor.clone(cloneConfig));
            pipeline.firstTouch();
            return pipeline;
        });
        BaseCloneableObject retVal;

        try {
            retVal = allocated.get();
        } catch (Throwable t) {
            throw new RuntimeException(
                    "Failed to construct the BaseCloneableObject.", t);
        }

        if (createdExecutor) {
            executor.shutdownNow();
        }
        return retVal;
    }

    @Override
    public void close() {
        try {
            try {
                if (this.fragment != null) {
                    this.fragment.close();
                }
            } catch (Exception e) {
                this.logger.error("Failed to close {}", this.fragment.getClass(), e);
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
