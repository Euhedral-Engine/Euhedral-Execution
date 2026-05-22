package euhedral.io.frames;

import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.io.control_plane.RoutingPolicy;
import euhedral.io.generics.AbstractExecutor;
import euhedral.io.impl.FrameManager;
import lombok.Getter;
import lombok.Setter;

/// Base unit of work within the Clio Execution Engine.
///
/// This class encapsulates the state, routing hashes, and lifecycle logic required for execution by
/// an [`AbstractExecutor`][euhedral.io].
///
/// To maximize performance and minimize GC pressure, instances are designed for reuse via a
/// [FrameManager]. After execution completes, the frame is returned to its creator to be reset and
/// dispatched again.
///
/// To prevent performance degradation and maintain strict ordering guarantees, use
/// [`HasherApi`][euhedral.io.utils] to generate or mix hashes.
///
/// **Ordering:** Reliable sequencing depends on keeping the hash consistent across retries.
/// ```java
/// long idHash = frame.getIdHash();
/// final long seed = 123;
/// frame.randomizeHash(HasherApi.combine(idHash, seed));
/// ```
///
/// **Parallelism:** For even distribution across consumers, each frame's hash must be changed. The
/// seed only needs to be incremented by one to ensure this happens.
///
/// ```java
/// long idHash = frame.getIdHash();
/// long seed = 123;
/// frame.randomizeHash(HasherApi.combine(idHash, seed++));
/// ```
@SuppressWarnings({"rawtypes", "unchecked", "unused"})
public abstract class AbstractFrame {

    protected final FrameManager recycler;
    public final CancelFrame cancel;

    @Getter
    private final long idHash;
    @Getter
    @Setter
    protected volatile long combinedHash;
    @Getter
    @Setter
    private CpuInfo origin;
    @Getter
    @Setter
    private RoutingPolicy routingPolicy;
    @Getter
    @Setter
    private long startNs;

    @Getter
    @Setter
    private long ingestNs;

    @Getter
    @Setter
    private boolean isOrdered;

    @Getter
    @Setter
    private boolean cancelledExecution = false;

    public AbstractFrame(long idHash, FrameManager recycler) {
        this.cancel = new CancelFrame(this);
        this.idHash = idHash;
        this.recycler = recycler;
        this.combinedHash = idHash;
    }

    public abstract long getSizeBytes();

    public abstract void execute();

    /// Mixes the combined hash with the seed.
    ///
    /// @param seed Hash seed
    public void randomizeHash(long seed) {
        long newHash = this.combinedHash;
        this.combinedHash = newHash ^ seed;
    }

    public abstract boolean isAlive();

    /// Kills the frame. This is for stopping the execution of this and all related frames.
    public abstract void kill();

    /// Defines what happens when execution is marked complete.
    public void doFinally() {
        recycle();
    }

    /// Resets the frame to its initial state.
    public final void reset() {
        startNs = 0;
        ingestNs = 0;
        cancelledExecution = false;
    }

    /// Sends the frame back to the creator for reuse.
    public final boolean recycle() {
        if (recycler != null) {
            return recycler.recycle(this);
        }
        return false;
    }

    /// Throws this class's error frame. This is used as an immediate way to stop execution of this
    /// frame. The [`AbstractExecutor`][AbstractExecutor] and
    /// [`ExecutionManager`][euhedral.io.ExecutionManager] handles this by default.
    public final void throwMeAsError() {
        throw this.cancel;
    }
}
