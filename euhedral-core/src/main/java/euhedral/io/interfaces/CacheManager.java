package euhedral.io.interfaces;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.io.config.CloneConfig;
import java.util.concurrent.Callable;

public interface CacheManager extends CloneableObject {

    void setDownstreamPressureMonitor(Callable<Double> pressure);

    CacheManager clone(CloneConfig cloneConfig);

    default CacheManager clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
        return clone(cloneConfig);
    }
}
