package io.euhedral_execution.core.generics;

import io.euhedral_execution.core.frames.AbstractFrame;
import java.util.function.Consumer;
import java.util.function.Function;

/// An interface for defining where data comes from.
///
/// Only the lattice's workers call `request()` and `pull()`. Work that comes from outside (a submitter, a completion
/// poller, another runtime) is offered into a queue the source owns, an MPSC that the source's `pull()` and `request()`
/// take from on the workers' own visits, and the outside thread returns at once: it never calls `request()` or `pull()`
/// to get its work admitted. A `pull()` pushes nothing, so a call from outside that finds the source held by a worker's
/// `pull()` waits for the lattice's next `request()` of that source.
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

    /// Signals demand to the source. Implementations may push during this call or later from another
    /// thread. Each downstream `push()` is a separate frame-admission event and selects the routing
    /// generation that is live when that push begins; returning from `request()` does not reserve a
    /// routing generation for future asynchronous frames.
    void request(long demand);

    void complete();

    boolean isComplete();
}
