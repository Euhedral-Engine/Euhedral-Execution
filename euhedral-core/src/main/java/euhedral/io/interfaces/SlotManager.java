package euhedral.io.interfaces;

import euhedral.io.control_plane.CloneConfig;
import euhedral.io.utils.PinnedThreadExecutor;

public interface SlotManager extends CloneableObject, AutoCloseable {

    double getPressure();

    PinnedThreadExecutor getPinnedExecutor();

    SlotManager clone(CloneConfig cloneConfig);
}
