package euhedral.io.frames;

import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.hashing.HasherApi;
import euhedral.io.control_plane.ControlPlaneFragment;
import euhedral.io.control_plane.RoutingPolicy;
import euhedral.io.generics.AbstractExecutor;
import euhedral.io.impl.FrameManager;
import java.lang.invoke.VarHandle;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NonNull;

/// ## Base unit of work within Euhedral Core
///
/// A frame is the smallest unit of execution in the system.
///
/// It carries execution state, routing hashes, and lifecycle hooks required by an
/// [`AbstractExecutor`][euhedral.io].
///
/// Frames are designed to be *hot-path reusable*. They are not created and discarded like typical
/// tasks. Instead, they are recycled through a [FrameManager] to avoid GC churn and keep allocation
/// pressure near zero.
///
/// Once execution completes, the frame is returned to its origin, reset, and potentially dispatched
/// again.
///
/// ---
///
/// ### Hashing & Routing
///
/// Frames are routed based on a `routingHash`. This is what determines how work spreads across
/// parallel paths.
///
/// For stable ordering, keep the hash consistent across retries:
///
/// ```java
/// long idHash = frame.getIdHash();
/// final long seed = 123;
/// frame.randomizeHash(HasherApi.combine(idHash, seed));
/// ```
///
/// For parallel distribution, vary the seed so frames naturally spread out:
///
/// ```java
/// long idHash = frame.getIdHash();
/// long seed = 123;
/// frame.randomizeHash(HasherApi.combine(idHash, seed++));
/// ```
///
/// ---
///
/// ### Lifecycle Notes
///
/// - `execute()` -> does the work
/// - `kill()` -> hard stop (this and related work)
/// - `isAlive()` -> soft liveness check (engine may cancel if false)
/// - `doFinally()` -> post-execution hook (safe mutation point)
///
/// ---
///
/// ### Mental model
///
/// Think of a frame as a tiny packet of work that keeps getting reshaped and forwarded until the
/// system is done with it.
///
/// It’s lightweight on purpose.
///
/// It doesn’t want to be expensive.
///
/// It just wants to move.
///
/// (And then get reused.)
@SuppressWarnings({"rawtypes", "unchecked", "unused"})
public abstract class AbstractFrame {

    protected final FrameManager recycler;
    public final CancelSignal cancel;

    @Getter
    private final long idHash;
    @Getter
    private long routingHash;
    @Getter @Setter
    private CpuInfo origin;
    @Setter
    private RoutingPolicy routingPolicy;

    @Getter @Setter
    private boolean cancelledExecution = false;

    public AbstractFrame(long idHash, FrameManager recycler) {
        this.cancel = new CancelSignal();
        this.idHash = idHash;
        this.recycler = recycler;
        this.routingHash = idHash;
    }

    /// Does the thing.
    public abstract void execute();

    /// Sets the routingHash by mixing the idHash with the seed.
    public final void randomizeHash(long seed) {
        seed = HasherApi.mix(seed);
        this.routingHash = this.idHash ^ seed;
    }

    /// Liveness check.
    ///
    /// If this returns `false`, the engine is allowed to cancel execution.
    public abstract boolean isAlive();

    /// Hard stop for this frame (and related execution).
    public abstract void kill();

    /// Post-execution hook.
    ///
    /// Called after execution completes. At this point it is safe to mutate frame state.
    public void doFinally() {
        recycle();
    }

    /// Resets execution state so the frame can be reused.
    public final void reset() {
        this.cancelledExecution = false;
        VarHandle.releaseFence();
    }

    /// Returns the frame to the recycler for reuse.
    public final void recycle() {
        if (this.recycler != null) {
            this.recycler.recycle(this);
        }
    }

    public final @NonNull RoutingPolicy getRoutingPolicy() {
        return this.routingPolicy == null ? RoutingPolicy.ANYWHERE : this.routingPolicy;
    }

    /// Throws the internal cancellation error used to stop execution immediately.
    ///
    /// Handled by [`AbstractExecutor`][AbstractExecutor] and
    /// [`ControlPlaneFragment`][ControlPlaneFragment].
    public final void throwMeAsError() {
        throw this.cancel;
    }

    public final boolean isOrdered() {
        return this.idHash == this.routingHash;
    }

    /// This class is thrown as a cancellation signal. This signal is automatically handled by the
    /// [ControlPlaneFragment][euhedral.io.control_plane.ControlPlaneFragment] and
    /// [AbstractExecutor][euhedral.io.generics.AbstractExecutor].
    public final class CancelSignal extends RuntimeException {
        public final AbstractFrame payload;

        public CancelSignal() {
            super(null, null, false, false);
            this.payload = AbstractFrame.this;
        }
    }
}
