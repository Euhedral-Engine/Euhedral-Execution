package euhedral.io.frames;

import euhedral.io.control_plane.RoutingPolicy;
import euhedral.io.errors.WorkCancelled;
import euhedral.io.flow_control.LockFreeSink;
import euhedral.io.resource_monitoring.NumaMapper.OriginLocation;
import euhedral.io.utils.MpscFrameRecycler;
import lombok.Getter;
import lombok.Setter;

/**
 * Base unit of work within the Clio Execution Fabric.
 *
 * <p>This class encapsulates the state, routing hashes, and lifecycle logic required for
 * execution by an {@link euhedral.io.AbstractExecutor}.
 *
 * <p>To maximize performance and minimize GC pressure, instances are designed for reuse
 * via an {@link MpscFrameRecycler}. After execution completes, the frame is returned to its creator
 * to be reset and dispatched again.
 *
 * <p><b>Ordering:</b> Reliable sequencing depends on unique, well-distributed hashes.
 * Use {@link euhedral.io.utils.KeyHasher} to prevent collisions that can degrade performance or break
 * ordering guarantees.
 */
public abstract class AbstractFrame extends WorkCancelled {

    private final long idHash;
    private final MpscFrameRecycler recycler;
    @Getter
    @Setter
    protected volatile long combinedHash;
    @Getter
    @Setter
    private OriginLocation origin;
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

    public AbstractFrame(long idHash, MpscFrameRecycler recycler) {
        this.idHash = idHash;
        this.recycler = recycler;
        this.combinedHash = idHash;
    }

    public final long getIdHash() {
        return idHash;
    }

    public abstract long getSizeBytes();

    /**
     * Mixes the combined hash with the seed.
     *
     * @param seed Hash seed
     */
    public void randomizeHash(long seed) {
        long newHash = this.combinedHash;
        this.combinedHash = newHash ^ seed;
    }

    public void ack() {

    }

    public abstract boolean isAlive();

    /**
     * Sets the completionSink for recording work completion.
     *
     * @param completionSink Sink to return the frame to.
     */
    public final void setCompletionSink(LockFreeSink completionSink) {
        this.completionSink = completionSink;
    }

    /**
     * Sets a password for notifying the completion of an execution.
     *
     * @param password notifyCompletePassword
     */
    public final void setNotifyCompletePassword(long password) {
        if (notifyCompletePassword == 0) {
            this.notifyCompletePassword = password;
        }
    }

    /**
     * Notifies the upstream caller to record the execution. The caller must pass the password they
     * generated at the start of execution.
     *
     * @param password notifyCompletePassword password
     */
    public final void notifyComplete(long password) {
        if (notifyCompletePassword == password) {
            while (!completionSink.relaxedOffer(this)) {
                Thread.onSpinWait();
            }
            notifyCompletePassword = 0;
        }
    }

    /**
     * Kills the frame. One use case is to have execution kill every frame that shares an atomic
     * value with this frame when they cannot execute the work.
     *
     */
    public abstract void kill();

    /**
     * Defines what happens when execution is marked complete.
     *
     */
    public void doFinally() {
        reset();
        recycle();
    }

    public final void reset() {
        combinedHash = idHash;
        startNs = 0;
        ingestNs = 0;
        completionSink = null;
        notifyCompletePassword = 0;
        cancelledExecution = false;
    }

    /**
     * Sends the frame back to the creator for reuse.
     */
    public final void recycle() {
        if (recycler != null) {
            recycler.recycle(this);
        }
    }

    /**
     * Throws this class as an error. This is used as a way to quickly stop execution of this frame.
     * {@link com.ccri.euhedral.common.io.dispatch AbstractExecutor} handles this by default.
     * UpstreamHandle callers should handle this error.
     */
    public final void throwMeAsError() {
        throw this;
    }
}
