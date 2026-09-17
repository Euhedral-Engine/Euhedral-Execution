package io.euhedral_execution.core.control_plane;

public final class FragmentControlConfig {
    public static final long DEFAULT_PARK_NS = 15_000L;

    private FragmentControlConfig() {}

    /// Execution strategies selected only at completed-batch boundaries.
    public enum ExecutionPath {
        IDLE,
        DIRECT,
        STAGED
    }
}
