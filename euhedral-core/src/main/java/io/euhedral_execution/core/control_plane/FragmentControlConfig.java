package io.euhedral_execution.core.control_plane;

import io.euhedral_execution.core.config.IdlePolicy;

public final class FragmentControlConfig {
    public static final String PRODUCTIVITY_THRESHOLD_WEIGHT = "euhedral.productivity.thresholdWeight";
    public static final String PRODUCTIVITY_GATE_MODE = "euhedral.calibration.productivityGateMode";
    public static final String FORCED_ACTIVE_PARTICIPANT_COUNT = "euhedral.calibration.forcedActiveParticipantCount";
    public static final String CACHE_PARK_NS = "euhedral.fragment.cache.parkNs";
    public static final String PARTICIPATION_POLICY_MODE = "euhedral.calibration.participationPolicyMode";

    public static final long DEFAULT_PARK_NS = 15_000L;
    public static final long DEFAULT_CACHE_PARK_NS = IdlePolicy.DEFAULT_IDLE_PARK_NS;
    public static final String CACHE_ACTUATOR_VERSION = "cache-v1";

    private FragmentControlConfig() {}

    /// Execution strategies selected only at completed-batch boundaries.
    public enum ExecutionPath {
        IDLE,
        DIRECT,
        STAGED
    }
}
