package euhedral.io.interfaces;

import euhedral.io.control_plane.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.hardware_utils.pinning.PinnedThreadExecutor;

public interface PipelineExecutor extends CloneableObject {
    void reportErrorsTo(CloneableObject clone);

    void execute(AbstractFrame frame);

    PipelineExecutor clone(CloneConfig config);

    PipelineExecutor clone(CloneConfig config, PinnedThreadExecutor executorService);
}
