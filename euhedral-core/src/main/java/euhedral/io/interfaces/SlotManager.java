package euhedral.io.interfaces;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.control_plane.CloneConfig;

public interface SlotManager extends CloneableObject {

    double getPressure();

    PinnedThreadExecutor getPinnedExecutor();

    SlotManager clone(CloneConfig cloneConfig);

    default SlotManager clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
        return clone(cloneConfig);
    }
}
