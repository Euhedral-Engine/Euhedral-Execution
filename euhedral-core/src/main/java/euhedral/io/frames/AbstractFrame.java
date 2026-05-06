package euhedral.io.frames;

import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.io.control_plane.RoutingPolicy;
import euhedral.io.errors.WorkCancelled;
import euhedral.io.flow_control.LockFreeSink;
import euhedral.io.impl.FrameManager;
import lombok.Getter;
import lombok.Setter;

/// Base unit of work within the Clio Execution Engine.
///
/// This class encapsulates the state, routing hashes, and lifecycle logic required for execution by
/// an [`AbstractExecutor`][euhedral.io].
///
/// To maximize performance and minimize GC pressure, instances are designed for reuse via an
/// [FrameManager]. After execution completes, the frame is returned to its creator to be reset
/// and dispatched again.
///
/// To prevent performance degradation and maintain strict ordering guarantees, use
/// [`KeyHasher`][euhedral.io.utils] to generate hashes.
///
/// **Ordering:** Reliable sequencing depends on keeping the hash consistent across retries.
/// ```java
/// long idHash = frame.getIdHash();
/// final long seed = 123;
/// frame.randomizeHash(KeyHasher.combine(idHash, seed));
/// ```
///
/// **Parallelism:** For even distribution across consumers, each frame's hash must be changed.
///
/// ```java
/// long idHash = frame.getIdHash();
/// long seed = 0;
/// frame.randomizeHash(KeyHasher.combine(idHash, seed++));
/// ```
@SuppressWarnings("rawtypes")
public abstract class AbstractFrame extends WorkCancelled {

    protected final FrameManager recycler;
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

    private LockFreeSink completionSink;
    private long notifyCompletePassword;

    @Getter
    @Setter
    private boolean isOrdered;

    @Getter
    @Setter
    private boolean cancelledExecution = false;

    @Getter
    @Setter
    private boolean useVThread = false;

    public AbstractFrame(long idHash, FrameManager recycler) {
        this.idHash = idHash;
        this.recycler = recycler;
        this.combinedHash = idHash;
    }

    public abstract long getSizeBytes();

    /// Mixes the combined hash with the seed.
    ///
    /// @param seed Hash seed
    public void randomizeHash(long seed) {
        long newHash = this.combinedHash;
        this.combinedHash = newHash ^ seed;
    }

    public abstract boolean isAlive();

    /// Sets the completionSink for recording work completion.
    ///
    /// @param completionSink Sink to return the frame to.
    public final void setCompletionSink(LockFreeSink completionSink) {
        this.completionSink = completionSink;
    }

    /// Sets a password for notifying the completion of an execution.
    ///
    /// @param password notifyCompletePassword
    public final void setNotifyCompletePassword(long password) {
        if (notifyCompletePassword == 0) {
            this.notifyCompletePassword = password;
        }
    }

    /// Notifies the upstream caller to record the execution. The caller must pass the password they
    /// generated at the start of execution.
    ///
    /// @param password notifyCompletePassword password
    public final void notifyComplete(long password) {
        if (notifyCompletePassword == password) {
            while (!completionSink.relaxedOffer(this)) {
                Thread.onSpinWait();
            }
            notifyCompletePassword = 0;
        }
    }

    /// Kills the frame. This is for stopping the execution of this and all related frames.
    public abstract void kill();

    /// Defines what happens when execution is marked complete.
    public void doFinally() {
        reset();
        recycle();
    }

    /// Resets the frame to its initial state.
    public final void reset() {
        combinedHash = idHash;
        startNs = 0;
        ingestNs = 0;
        completionSink = null;
        notifyCompletePassword = 0;
        cancelledExecution = false;
    }

    /// Sends the frame back to the creator for reuse.
    @SuppressWarnings("unchecked")
    public final boolean recycle() {
        if (recycler != null) {
            return recycler.recycle(this);
        }
        return false;
    }

    /// Throws this class as an error. This is used as a cancellation signal and an immediate way to stop execution of this frame.
    /// [`AbstractExecutor`][euhedral.common.io.dispatch] handles this by default.
    public final void throwMeAsError() {
        throw this;
    }
}
