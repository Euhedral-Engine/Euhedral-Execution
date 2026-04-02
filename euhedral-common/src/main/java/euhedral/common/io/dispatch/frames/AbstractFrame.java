package euhedral.common.io.dispatch.frames;

import euhedral.common.io.dispatch.DispatchOrders;
import euhedral.common.io.dispatch.errors.WorkCancelled;
import euhedral.common.io.dispatch.utils.KeyHasher;
import euhedral.common.io.dispatch.utils.MpscFrameRecycler;
import lombok.Getter;
import lombok.Setter;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.core.publisher.Sinks.Many;

/**
 * Base unit of work within the Clio Execution Fabric.
 *
 * <p>This class encapsulates the state, routing hashes, and lifecycle logic required for
 * execution by an {@link euhedral.common.io.dispatch.AbstractExecutor}.
 *
 * <p>To maximize performance and minimize GC pressure, instances are designed for reuse
 * via an {@link MpscFrameRecycler}. After execution completes, the frame is returned to its creator
 * to be reset and dispatched again.
 *
 * <p><b>Ordering:</b> Reliable sequencing depends on unique, well-distributed hashes.
 * Use {@link euhedral.common.io.dispatch.utils.KeyHasher} to prevent collisions that can
 * degrade performance or break ordering guarantees.
 */
public abstract class AbstractFrame extends WorkCancelled {

    private final long idHash;
    private final MpscFrameRecycler recycler;
    @Getter
    @Setter
    protected volatile long combinedHash;
    @Getter
    @Setter
    private long destinationHash;
    private DispatchOrders dispatchOrders;

    @Getter
    @Setter
    private long startNs;

    @Getter
    @Setter
    private long ingestNs;

    private Many<AbstractFrame> completionSink;
    private long notifyCompletePassword;

    @Getter
    @Setter
    private boolean cancelledExecution = false;

    @Getter
    @Setter
    private boolean useVThread = false;

    public AbstractFrame(long idHash, long destinationHash,
            MpscFrameRecycler recycler) {
        this.idHash = idHash;
        this.destinationHash = destinationHash;
        this.recycler = recycler;
        this.combinedHash = KeyHasher.combine(idHash, destinationHash);
    }

    public abstract String getId();

    public final long getIdHash() {
        return idHash;
    }

    public abstract long getSizeBytes();

    public final DispatchOrders getDispatchOrders() {
        return dispatchOrders;
    }

    public final void setDispatchOrders(DispatchOrders orders) {
        this.dispatchOrders = orders;
        this.combinedHash = idHash ^ orders.hash();
    }

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

    public boolean isOrdered() {
        if (dispatchOrders == null) {
            return false;
        }
        return dispatchOrders.isOrdered();
    }

    public abstract boolean isAlive();

    /**
     * Sets the completionSink for recording work completion.
     *
     * @param completionSink Sink to return the frame to.
     */
    public final void setCompletionSink(Many<AbstractFrame> completionSink) {
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
    public final void notifyComplete(long startNs, long password) {
        this.startNs = startNs;
        if (notifyCompletePassword == password) {
            EmitResult result;
            while (!(result = completionSink.tryEmitNext(this)).isSuccess()) {
                if (result == EmitResult.FAIL_CANCELLED || result == EmitResult.FAIL_TERMINATED
                        || result == EmitResult.FAIL_ZERO_SUBSCRIBER) {
                    this.kill();
                    throw new IllegalStateException(
                            "CRITICAL: No upstream connection to signal cancellation.");
                }
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
        setCancelledExecution(false);
        recycle();
    }

    /**
     * Sends the frame back to the creator for reuse.
     */
    public final void recycle() {
        recycler.recycle(this);
    }

    /**
     * Throws this class as an error. This is used as a way to quickly stop execution of this frame.
     * {@link euhedral.common.io.dispatch AbstractExecutor} handles this by default. UpstreamHandle
     * callers should handle this error.
     */
    public final void throwMeAsError() {
        throw this;
    }
}
