package euhedral.io.interfaces;

import euhedral.io.control_plane.CloneConfig;
import java.util.concurrent.Callable;

public interface CacheManager extends CloneableObject {

    void setDownstreamPressureMonitor(Callable<Double> pressure);

    CacheManager clone(CloneConfig cloneConfig);
}
