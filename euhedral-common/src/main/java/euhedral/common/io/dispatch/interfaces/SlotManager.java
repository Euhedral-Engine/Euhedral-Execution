package euhedral.common.io.dispatch.interfaces;

import euhedral.common.io.dispatch.control_plane.CloneConfig;
import euhedral.common.io.dispatch.utils.PinnedThreadExecutor;

public interface SlotManager extends CloneableObject, AutoCloseable {

    double getPressure();

    PinnedThreadExecutor getPinnedExecutor();

    SlotManager clone(CloneConfig cloneConfig);
}
