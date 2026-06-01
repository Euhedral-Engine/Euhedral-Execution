package euhedral.io.generics;

import euhedral.io.frames.AbstractFrame;
import java.util.function.Consumer;

/// An interface for defining where data comes from
public interface LatticeSource {

    default void startUFlowWith(LatticeTerminal terminal) {
        terminal.addUpstream(this);
    }

    void addDownstream(LatticeReceiver downstream);

    /// A synchronous method called by downstreams to collect work without triggering a `push()`.
    ///
    /// Rules:
    /// - Demand from this call cannot be accumulated like `request()`
    /// - Frames must be passed directly to the consumer and not pushed
    /// - Frames must not be generated to meet the demand
    void pull(Consumer<AbstractFrame> consumer, long demand);

    /// A synchronous method called by downstreams to collect work without triggering a `push`
    ///
    /// This is a less strict version of pull where generating frames to meet the demand is allowed.
    ///
    /// Rules:
    /// - Demand from this call cannot be accumulated like `request()`
    /// - Frames must be passed directly to the consumer and not pushed
    default void userPull(Consumer<AbstractFrame> consumer, long demand) {
        pull(consumer, demand);
    }

    void request(long demand);

    void complete();
}
