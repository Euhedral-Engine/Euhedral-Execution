package euhedral.io.resource_monitoring.providers;

import euhedral.io.resource_monitoring.SystemUtilization.SystemSnapshot;

public interface ResourceProvider {
    SystemSnapshot getSnapshot();
}
