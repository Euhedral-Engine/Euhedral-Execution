package io.euhedral_execution.hardware_utils.common;

import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;

public interface SystemSnapshotProvider {
    SystemSnapshot getSnapshot();
}
