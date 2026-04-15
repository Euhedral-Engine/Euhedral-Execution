package euhedral.io.interfaces;

import euhedral.io.control_plane.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.PinnedThreadExecutor;

public interface PipelineExecutor extends CloneableObject {
    void reportErrorsTo(CloneableObject clone);

    void execute(AbstractFrame frame);

    PipelineExecutor clone(CloneConfig config);

    PipelineExecutor clone(CloneConfig config, PinnedThreadExecutor executorService);
}
