package io.euhedral_execution.hardware_utils.monitor.internal;

import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.HardwareUtilization;
import io.euhedral_execution.hardware_utils.topology.TopologyMapper;

public interface TopologyUpdater {

    static TopologyUpdater from(TopologyMapper mapper) {
        return mapper::update;
    }

    void update(HardwareUtilization utilization);
}
