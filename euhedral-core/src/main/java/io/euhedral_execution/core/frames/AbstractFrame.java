package io.euhedral_execution.core.frames;

import io.euhedral_execution.core.control_plane.ControlPlaneFragment;
import io.euhedral_execution.core.flow_control.RoutingPolicy;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.core.impl.FrameManager;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hashing.HasherApi;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NonNull;

/// ## Base unit of work within Euhedral Core
///
/// A frame is the smallest unit of execution in the system.
///
/// It carries execution state, routing hashes, and lifecycle hooks required by an
/// [`AbstractExecutor`][io.euhedral_execution.core.generics].
///
/// Frames are designed to be *reusable*. They are not created and discarded like typical
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
/// For stable ordering, keep the hash consistent across instances:
///
/// ```java
/// long idHash = HasherApi.mix(1234);
/// Frame frame1 = new Frame(idHash);
/// Frame frame2 = new Frame(idHash);
/// ```
///
/// For parallel distribution, vary the seed so frames naturally spread out:
///
/// ```java
/// long seed = 123;
/// frame1.randomizeHash(HasherApi.mix(seed++));
/// frame2.randomizeHash(HasherApi.mix(seed++));
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
/// - `doFinallyWithError()` -> post-execution hook after an uncaught exception (safe mutation point)
@SuppressWarnings({"rawtypes", "unchecked", "unused"})
public abstract class AbstractFrame {

    public static final CancelSignal CANCEL_SIGNAL = new CancelSignal();

    protected final FrameManager recycler;

    @Getter
    private final long idHash;
    @Getter
    private long routingHash;
    @Getter @Setter
    private CpuInfo origin;
    @Setter
    private RoutingPolicy routingPolicy;

    public AbstractFrame(long idHash, FrameManager recycler) {
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

    /// Hard stop for this frame.
    public abstract void kill();

    /// Post-execution hook.
    ///
    /// Called after execution completes. At this point it is safe to mutate frame state.
    public void doFinally() {
        recycle();
    }

    /// Post-execution hook.
    ///
    /// Called after execution is canceled due to an uncaught error. At this point it is safe to mutate frame state.
    public void doFinallyWithError(Throwable t) {
        recycle();
    }

    /// Resets the routingHash to the idHash.
    public void reset() {
        this.routingHash = idHash;
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
    public final void throwCancelSignal() {
        throw CANCEL_SIGNAL;
    }

    public final boolean isOrdered() {
        return this.idHash == this.routingHash;
    }

    /// This class is thrown as a cancellation signal. This signal is automatically handled by the
    /// [ControlPlaneFragment][ControlPlaneFragment] and
    /// [AbstractExecutor][AbstractExecutor].
    public static final class CancelSignal extends RuntimeException {
        private CancelSignal() {
            super(null, null, false, false);
        }
    }
}
