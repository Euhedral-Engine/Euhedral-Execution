package io.euhedral_execution.core.generics;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;

/// ## Base interface for everything below the
/// [`ControlPlaneShard`][io.euhedral_execution.core.control_plane.ControlPlaneShard]
///
/// Method Call Sequence if Using [`BaseCloneableObject`][io.euhedral_execution.core.impl.BaseCloneableObject]:
/// - clone()
/// - firstTouch()
/// - start()
/// - ingest() / output()
public interface CloneableObject {

    default CloneableObject clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
        return clone(cloneConfig);
    }

    CloneableObject clone(CloneConfig cloneConfig);

    default void start() {}

    /// Used by CloneableObjects that create objects on instantiation. This method will be called
    /// once before start(). Implementations should fill their queues, touch all their state
    /// objects, and then reset them. On Linux, this ensures data structures are allocated on the
    /// NUMA node closest to the cpu that needs them.
    default void firstTouch() {}

    default boolean isStarted() {
        return true;
    }

    default boolean ready() {
        return true;
    }

    default void update(CoreSnapshot coreSnapshot) {}

    default void input(LatticeSource stream) {}

    default LatticeSource output() {
        return null;
    }

    default boolean isDrained() {
        return true;
    }

    default void setDrainMode(boolean value) {}

    /**
     * Clears trial-specific buffered work and controller state before another benchmark policy is
     * activated. Implementations that own single-consumer queues must perform the reset on their
     * owning thread and acknowledge it before {@code deadlineNanos}.
     *
     * @return the estimated number of buffered frames removed
     */
    default long reset(long deadlineNanos) {
        return 0;
    }

    default void dumpLocks() {}

    default int getCore() {
        return -1;
    }

    default void close() {}
}
