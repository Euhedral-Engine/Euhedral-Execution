package io.euhedral_execution.hardware_utils.internal.monitor;

import io.euhedral_execution.hardware_utils.TopologyMapper;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.HardwareUtilization;

public interface TopologyUpdater {

    static TopologyUpdater from(TopologyMapper mapper) {
        return mapper::update;
    }

    void update(HardwareUtilization utilization);
}
