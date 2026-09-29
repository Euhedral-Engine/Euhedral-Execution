package io.euhedral_execution.hardware_utils.monitor;

import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.SystemSnapshot;

public interface SystemSnapshotProvider {
    SystemSnapshot getSnapshot();
}
