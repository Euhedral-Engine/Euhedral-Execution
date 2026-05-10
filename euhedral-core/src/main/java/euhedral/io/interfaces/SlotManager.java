package euhedral.io.interfaces;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;

public interface SlotManager extends CloneableObject {

    double getPressure();

    PinnedThreadExecutor getPinnedExecutor();

    SlotManager clone(CloneConfig cloneConfig);

    default SlotManager clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
        return clone(cloneConfig);
    }
}
