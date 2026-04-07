package euhedral.io.interfaces;

import euhedral.io.control_plane.CloneConfig;
import java.util.concurrent.Callable;

public interface DispatchPreProcess extends CloneableObject {

    void setDownstreamPressureMonitor(Callable<Double> pressure);

    DispatchPreProcess clone(CloneConfig cloneConfig);
}
