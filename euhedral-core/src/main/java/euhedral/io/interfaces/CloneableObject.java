package euhedral.io.interfaces;

import euhedral.io.control_plane.CloneConfig;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.utils.PinnedThreadExecutor;
import euhedral.io.utils.SystemUtilization.CoreSnapshot;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

public interface CloneableObject extends AutoCloseable {

    default CloneableObject clone(CloneConfig cloneConfig, PinnedThreadExecutor executor) {
        return clone(cloneConfig);
    }

    CloneableObject clone(CloneConfig cloneConfig);

    default void start() {
    }

    default boolean isStarted() {
        return true;
    }

    default void update(CoreSnapshot coreSnapshot) {
    }

    default void ingest(Publisher<AbstractFrame> flux) {
    }

    default Publisher<AbstractFrame> process(Publisher<AbstractFrame> flux) {
        return flux;
    }

    default Publisher<AbstractFrame> output() {
        return Flux.empty();
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

    record Failure(long duration, AbstractFrame frame, Exception exception) {

    }
}
