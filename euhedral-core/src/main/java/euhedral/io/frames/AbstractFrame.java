package euhedral.io.frames;

import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.hashing.HasherApi;
import euhedral.io.control_plane.ControlPlaneFragment;
import euhedral.io.control_plane.RoutingPolicy;
import euhedral.io.generics.AbstractExecutor;
import euhedral.io.impl.FrameManager;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import lombok.Getter;
import lombok.Setter;

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

    protected static final VarHandle ROUTING_HASH;

    static {
        try {
            ROUTING_HASH = MethodHandles.lookup().findVarHandle(AbstractFrame.class, "routingHash", long.class);
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    protected final FrameManager recycler;
    public final CancelFrame cancel;

    @Getter
    private final long idHash;
    @Getter
    private long routingHash;
    @Getter @Setter
    private CpuInfo origin;
    @Getter @Setter
    private RoutingPolicy routingPolicy;
    @Getter @Setter
    private long startNs;

    @Getter @Setter
    private long ingestNs;

    @Getter @Setter
    private boolean cancelledExecution = false;

    public AbstractFrame(long idHash, FrameManager recycler) {
        this.cancel = new CancelFrame(this);
        this.idHash = idHash;
        this.recycler = recycler;
        this.routingHash = idHash;
    }

    /// Does the thing.
    public abstract void execute();

    /// Mixes the combined hash with the seed.
    public final void randomizeHash(long seed) {
        seed = HasherApi.mix(seed);
        long newHash = this.routingHash;
        ROUTING_HASH.setRelease(this, newHash ^ seed);
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
        this.startNs = 0;
        this.ingestNs = 0;
        this.cancelledExecution = false;
        ROUTING_HASH.setRelease(this, this.idHash);
    }

    /// Returns the frame to the recycler for reuse.
    public final boolean recycle() {
        if (this.recycler != null) {
            return this.recycler.recycle(this);
        }
        return false;
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

    public abstract long getSizeBytes();
}
