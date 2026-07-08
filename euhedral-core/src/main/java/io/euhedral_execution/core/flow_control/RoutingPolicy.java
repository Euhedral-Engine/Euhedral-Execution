package io.euhedral_execution.core.flow_control;

public enum RoutingPolicy {
    ANYWHERE(0),
    SOCKET_LOCAL(1),
    CACHE_LOCAL(2);

    public final int level;

    RoutingPolicy(int level) {
        this.level = level;
    }
}
