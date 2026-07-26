from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match in {path}, found {count}: {old[:100]!r}")
    file.write_text(text.replace(old, new, 1))


cloneable = "euhedral-core/src/main/java/io/euhedral_execution/core/generics/CloneableObject.java"
replace_once(cloneable,
"""    default void dumpLocks() {
""",
"""    /**
     * Clears trial-specific buffered work and controller state before another benchmark policy is
     * activated. Implementations that own single-consumer queues must perform the reset on their
     * owning thread and acknowledge it before {@code deadlineNanos}.
     *
     * @return the estimated number of buffered frames removed
     */
    default long resetForNextTrial(long deadlineNanos) {
        return 0;
    }

    default void dumpLocks() {
""")

base = "euhedral-core/src/main/java/io/euhedral_execution/core/impl/BaseCloneableObject.java"
replace_once(base,
"""    @Override
    public int getCore() {
""",
"""    @Override
    public long resetForNextTrial(long deadlineNanos) {
        long cleared = 0;
        if (this.fragment != null) {
            cleared += this.fragment.resetForNextTrial(deadlineNanos);
        }
        if (this.executor != null) {
            cleared += this.executor.resetForNextTrial(deadlineNanos);
        }
        return cleared;
    }

    @Override
    public int getCore() {
""")

vertex = "euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/LatticeVertex.java"
replace_once(vertex,
"""    /// Adds the interceptor to the upstream. If it is a [LatticeEdge], it bubbles it up and sets
""",
"""    /**
     * Forcefully clears this vertex's remote routing caches. The caller must first stop ingress or
     * place the owning graph in drain mode so producers cannot race the reset.
     *
     * @return the estimated number of cached frames removed
     */
    public long clearCachedFrames() {
        if (!this.hasCache) {
            return 0;
        }

        long cleared = Math.max(0, this.cacheCount.sumAndReset());
        for (var queue : this.remoteCache) {
            if (queue != null) {
                queue.clear();
            }
        }
        this.cacheHead.remove();
        return cleared;
    }

    /// Adds the interceptor to the upstream. If it is a [LatticeEdge], it bubbles it up and sets
""")

cache = "euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneCache.java"
replace_once(cache,
"""    public final long getMaxLocalCacheCount() {
""",
"""    /**
     * Clears the fragment-local MPSC cache. This must be invoked by the fragment's pinned consumer
     * thread after ingress has been frozen.
     */
    protected final long clearLocalCacheOnOwnerThread() {
        if (this.localCache == null) {
            return 0;
        }

        long cleared = Math.max(0, (long) TOTAL_COUNT.getOpaque(this));
        this.localCache.clear();
        this.cacheTerminal.reset();
        TOTAL_COUNT.setRelease(this, 0L);
        return cleared;
    }

    public final long getMaxLocalCacheCount() {
""")

fragment = "euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneFragment.java"
replace_once(fragment,
"""import java.lang.invoke.VarHandle;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
""",
"""import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
""")
replace_once(fragment,
"""    private final AtomicBoolean running = new AtomicBoolean(false);
    private final PinnedThreadExecutor mainExecutor;
""",
"""    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong resetRequested = new AtomicLong();
    private final AtomicLong resetCompleted = new AtomicLong();
    private final AtomicLong resetCleared = new AtomicLong();
    private final PinnedThreadExecutor mainExecutor;
""")
replace_once(fragment,
"""            while (keepRunning()) {
                long newUpCount = context.upstream.getCachedUpCount();
""",
"""            while (keepRunning()) {
                serviceResetRequest();
                if (this.benchmarkMode && this.actionPicker.halted()) {
                    Thread.onSpinWait();
                    continue;
                }

                long newUpCount = context.upstream.getCachedUpCount();
""")
replace_once(fragment,
"""                if(this.benchmarkMode && this.actionPicker.halted()) {
                    this.state.reset();
                    Thread.onSpinWait();
                    continue;
                }

""", "")
replace_once(fragment,
"""    private long idleSpin(FlowThread.FlowContext threadContext) {
        while (keepRunning()) {
            long upCount = threadContext.upstream.getTrueUpstreamCount();
""",
"""    private long idleSpin(FlowThread.FlowContext threadContext) {
        while (keepRunning()) {
            serviceResetRequest();
            if (this.benchmarkMode && this.actionPicker.halted()) {
                return 0;
            }
            long upCount = threadContext.upstream.getTrueUpstreamCount();
""")
replace_once(fragment,
"""    @Override
    public ControlPlaneFragment clone(CloneConfig cloneConfig) {
""",
"""    private void serviceResetRequest() {
        long requested = this.resetRequested.getAcquire();
        if (requested <= this.resetCompleted.getOpaque() || this.state == null) {
            return;
        }

        long cleared = super.clearLocalCacheOnOwnerThread();
        this.state.reset();
        this.resetCleared.setRelease(cleared);
        this.resetCompleted.setRelease(requested);
    }

    @Override
    public long resetForNextTrial(long deadlineNanos) {
        if (this.state == null) {
            return 0;
        }
        if (!this.running.getAcquire()) {
            long cleared = super.clearLocalCacheOnOwnerThread();
            this.state.reset();
            return cleared;
        }

        long request = this.resetRequested.incrementAndGet();
        Thread owner = this.mainThread;
        if (owner != null) {
            LockSupport.unpark(owner);
        }
        while (this.resetCompleted.getAcquire() < request
                && this.running.getAcquire() && System.nanoTime() < deadlineNanos) {
            LockSupport.parkNanos(5_000L);
        }
        if (this.resetCompleted.getAcquire() < request) {
            throw new IllegalStateException(
                    "Timed out resetting fragment cache on core " + this.core);
        }
        return this.resetCleared.getAcquire();
    }

    @Override
    public ControlPlaneFragment clone(CloneConfig cloneConfig) {
""")
replace_once(fragment,
"""            this.batchStart = 0;
            this.batchSize = 2;
            this.completed = 0;
        }
""",
"""            this.batchStart = 0;
            this.batchSize = 2;
            this.completed = 0;
            this.upstreamCount = 0;
            this.totalExecutions = 0;
            Arrays.fill(this.actionInputs, 0.0);
        }
""")

shard = "euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneShard.java"
replace_once(shard,
"""    public boolean isStarted() {
""",
"""    long resetForNextTrial(long deadlineNanos) {
        if (!this.started.getAcquire()) {
            return 0;
        }
        while (this.rebalancing.getAcquire() && System.nanoTime() < deadlineNanos) {
            Thread.onSpinWait();
        }
        if (this.rebalancing.getAcquire()) {
            throw new IllegalStateException(
                    "Timed out waiting for shard rebalance before trial reset: " + this.shardName);
        }

        LatticeVertex distributor = this.coreDistributor.getAcquire();
        if (distributor == null) {
            return 0;
        }

        distributor.setDrain(true);
        CloneableObject[] activeClones = this.clones.getAcquire();
        for (CloneableObject clone : activeClones) {
            if (clone != null) {
                clone.setDrainMode(true);
            }
        }

        long cleared = distributor.clearCachedFrames();
        try {
            for (CloneableObject clone : activeClones) {
                if (clone != null) {
                    cleared += clone.resetForNextTrial(deadlineNanos);
                }
            }
            return cleared;
        } finally {
            for (CloneableObject clone : activeClones) {
                if (clone != null) {
                    clone.setDrainMode(false);
                }
            }
            distributor.setDrain(false);
        }
    }

    public boolean isStarted() {
""")

lattice = "euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneLattice.java"
replace_once(lattice,
"""    final AtomicBoolean rebalancing = new AtomicBoolean(false);
""",
"""    final AtomicBoolean rebalancing = new AtomicBoolean(false);
    final AtomicBoolean resetting = new AtomicBoolean(false);
""")
replace_once(lattice,
"""    void update(HardwareUtilization utilization) {
        int nextVersion = this.topology.getGlobalVersion();
""",
"""    void update(HardwareUtilization utilization) {
        if (this.resetting.getAcquire()) {
            return;
        }
        int nextVersion = this.topology.getGlobalVersion();
""")
replace_once(lattice,
"""    public int getActiveWorkers() {
""",
"""    /**
     * Freezes ingest and clears all socket-distributor and fragment-local caches before another
     * benchmark policy is activated. Ingest sources should be paused before calling this method.
     */
    public CacheReset resetForNextTrial(Duration timeout) {
        Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Reset timeout must be positive");
        }
        if (this.closed.getAcquire()) {
            throw new IllegalStateException("Cannot reset a closed ControlPlaneLattice");
        }
        if (!this.started.getAcquire()) {
            return new CacheReset(0, 0);
        }
        if (!this.resetting.compareAndSet(false, true)) {
            throw new IllegalStateException("A lattice reset is already in progress");
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        LatticeVertex controller = this.ingestController.getAcquire();
        try {
            while (this.rebalancing.getAcquire() && System.nanoTime() < deadline) {
                LockSupport.parkNanos(5_000L);
            }
            if (this.rebalancing.getAcquire()) {
                throw new IllegalStateException(
                        "Timed out waiting for global rebalance before trial reset");
            }

            if (controller != null) {
                controller.setDrain(true);
            }
            long cleared = controller == null ? 0 : controller.clearCachedFrames();
            for (ControlPlaneShard shard : this.shards) {
                if (shard != null) {
                    cleared += shard.resetForNextTrial(deadline);
                }
            }
            return new CacheReset(cleared, getActiveWorkers());
        } finally {
            if (controller != null) {
                controller.setDrain(false);
            }
            this.resetting.setRelease(false);
        }
    }

    public int getActiveWorkers() {
""")
replace_once(lattice,
"""    /// Whether all queues are empty and all in-progress work is completed for all CPUs managed by
""",
"""    public record CacheReset(long clearedFrames, int activeWorkers) {
    }

    /// Whether all queues are empty and all in-progress work is completed for all CPUs managed by
""")
