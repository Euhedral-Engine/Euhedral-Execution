package euhedral.io.interfaces;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.common.SystemUtilization.CoreSnapshot;
import euhedral.io.config.CloneConfig;
import euhedral.io.flow_control.LockFreeSink;

/// ## Base interface for everything below the [`ControlPlaneShard`][euhedral.io.control_plane.ControlPlaneShard]
///
/// Method Call Sequence if Using [`AbstractCloneablePipeline`][euhedral.io.AbstractCloneablePipeline]:
/// - clone()
/// - firstTouch()
/// - start()
/// - reportErrorsTo()
/// - ingest() / output()
public interface CloneableObject extends AutoCloseable {

    default CloneableObject clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
        return clone(cloneConfig);
    }

    CloneableObject clone(CloneConfig cloneConfig);

    default void start() {
    }

    /// Used by CloneableObjects that create objects on instantiation. This method will be called
    /// once before start(). Implementations should fill their queues, touch all their state
    /// objects, and then reset them. On Linux, this ensures they are allocated on the NUMA node
    /// closest to the cpu that needs them.
    default void firstTouch() {
    }

    default boolean isStarted() {
        return true;
    }

    default void update(CoreSnapshot coreSnapshot) {
    }

    default void input(ScaffoldingOrigin stream) {
    }

    default ScaffoldingOrigin output() {
        return null;
    }

    default LockFreeSink completeChannel() {
        return null;
    }

    default double getPressure() {
        return 0;
    }

    default boolean isDrained() {
        return true;
    }

    default void setDrainMode(boolean value) {

    }

    default void dumpLocks() {

    }

    default int getCore() {
        return -1;
    }
}
