package io.euhedral_execution.core.generics;

import io.euhedral_execution.core.frames.AbstractFrame;
import java.util.function.Consumer;
import java.util.function.Function;

/// An interface for defining where data comes from
public interface LatticeSource {

    void addDownstream(LatticeReceiver downstream);

    /// A synchronous method called by downstreams to collect work without triggering a `push()`.
    ///
    /// Rules:
    /// - Demand from this call cannot be accumulated like `request()`
    /// - Frames must be passed directly to the consumer and not pushed
    /// - Frames must not be generated to meet the demand
    /// - The implementation must stop the pull when the stop condition is true
    long pull(Consumer<AbstractFrame> consumer, Function<AbstractFrame, Boolean> stopCondition, long demand);

    void request(long demand);

    void complete();

    boolean isComplete();
}
