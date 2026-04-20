package euhedral.io.interfaces;

import euhedral.io.control_plane.CloneConfig;
import euhedral.io.hardware_utils.pinning.PinnedThreadExecutor;

public interface SlotManager extends CloneableObject {

    double getPressure();

    PinnedThreadExecutor getPinnedExecutor();

    SlotManager clone(CloneConfig cloneConfig);
}
