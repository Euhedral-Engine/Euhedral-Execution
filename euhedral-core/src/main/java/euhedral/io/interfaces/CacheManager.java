package euhedral.io.interfaces;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;

public interface CacheManager extends CloneableObject {

    CacheManager clone(CloneConfig cloneConfig);

    default CacheManager clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
        return clone(cloneConfig);
    }
}
