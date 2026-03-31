package euhedral.common.io.dispatch.interfaces;

import euhedral.common.io.dispatch.control_plane.CloneConfig;
import java.util.concurrent.Callable;

public interface DispatchPreProcess extends CloneableObject {

    void setDownstreamPressureMonitor(Callable<Double> pressure);

    DispatchPreProcess clone(CloneConfig cloneConfig);
}
