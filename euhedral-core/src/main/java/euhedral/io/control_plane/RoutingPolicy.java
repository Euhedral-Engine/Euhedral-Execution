package euhedral.io.control_plane;

public enum RoutingPolicy {
    ANY(0),
    SOCKET_LOCAL(1),
    CORE_LOCAL(2);

    public final int level;

    RoutingPolicy(int level) {
        this.level = level;
    }
}
