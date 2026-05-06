package euhedral.hardware_utils.common;

import euhedral.hardware_utils.common.SystemUtilization.SystemSnapshot;

public interface SystemSnapshotProvider {
    SystemSnapshot getSnapshot();
}
