package euhedral.io.interfaces;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.common.SystemUtilization.CoreSnapshot;
import euhedral.io.config.CloneConfig;
import euhedral.io.flow_control.LockFreeSink;
import euhedral.io.frames.AbstractFrame;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

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

    default void ingest(Publisher<? extends AbstractFrame> flux) {
    }

    default Publisher<? extends AbstractFrame> process(Publisher<? extends AbstractFrame> flux) {
        return flux;
    }

    default Publisher<? extends AbstractFrame> output() {
        return Flux.empty();
    }

    default LockFreeSink completeChannel() {
        return null;
    }

    default void errorChannel(Publisher<Failure> errorFlux) {

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

    record Failure(AbstractFrame frame, Exception exception) {

    }
}
