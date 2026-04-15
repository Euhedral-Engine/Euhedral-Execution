package euhedral.io.impl;

import euhedral.io.AbstractCloneablePipeline;
import euhedral.io.control_plane.CloneConfig;
import euhedral.io.interfaces.DispatchPreProcess;
import euhedral.io.interfaces.SlotManager;

public class FunctionalPipeline extends AbstractCloneablePipeline {

    public FunctionalPipeline(String name, CloneConfig cloneConfig,
            DispatchPreProcess preProcess,
            SlotManager slotManager,
            FunctionalExecutor executor) {
        super(name, cloneConfig, preProcess, slotManager, executor);
    }

    @Override
    public FunctionalPipeline hookOnClone(CloneConfig cloneConfig) {
        return new FunctionalPipeline(name, cloneConfig, preProcess, slotManager,
                (FunctionalExecutor) executor);
    }
}
