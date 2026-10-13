package io.euhedral_execution.hardware_utils.monitor;

import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.SystemSnapshot;
import org.jspecify.annotations.Nullable;

public interface SystemSnapshotProvider {
    @Nullable
    SystemSnapshot getSnapshot();
}
