from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one match in {path}, found {count}: {old[:80]!r}")
    file.write_text(text.replace(old, new, 1))


replace_once(
    "euhedral-core/src/main/java/io/euhedral_execution/core/generics/CloneableObject.java",
    """    default void setDrainMode(boolean value) {\n\n    }\n\n    default void dumpLocks() {\n""",
    """    default void setDrainMode(boolean value) {\n\n    }\n\n    /**\n     * Clears trial-specific buffered work and controller state before another benchmark policy is\n     * activated. Implementations that own single-consumer queues must perform the reset on their\n     * owning thread and acknowledge it before {@code deadlineNanos}.\n     *\n     * @return the estimated number of buffered frames removed\n     */\n    default long resetForNextTrial(long deadlineNanos) {\n        return 0;\n    }\n\n    default void dumpLocks() {\n""",
)

replace_once(
    "euhedral-core/src/main/java/io/euhedral_execution/core/impl/BaseCloneableObject.java",
    """    @Override\n    public int getCore() {\n""",
    """    @Override\n    public long resetForNextTrial(long deadlineNanos) {\n        long cleared = 0;\n        if (this.fragment != null) {\n            cleared += this.fragment.resetForNextTrial(deadlineNanos);\n        }\n        if (this.executor != null) {\n            cleared += this.executor.resetForNextTrial(deadlineNanos);\n        }\n        return cleared;\n    }\n\n    @Override\n    public int getCore() {\n""",
)

replace_once(
    "euhedral-core/src/main/java/io/euhedral_execution/core/flow_control/LatticeVertex.java",
    """    public boolean isDrained() {\n        if (!this.hasCache) {\n            return true;\n        }\n        for (var queue : this.remoteCache) {\n            if (queue != null && !queue.isEmpty()) {\n                return false;\n            }\n        }\n        return true;\n    }\n\n    /// Adds the interceptor to the upstream.\n""",
    """    public boolean isDrained() {\n        if (!this.hasCache) {\n            return true;\n        }\n        for (var queue : this.remoteCache) {\n            if (queue != null && !queue.isEmpty()) {\n                return false;\n            }\n        }\n        return true;\n    }\n\n    /**\n     * Forcefully clears this vertex's remote routing caches. The caller must first stop ingress or\n     * place the owning graph in drain mode so producers cannot race the reset.\n     *\n     * @return the estimated number of cached frames removed\n     */\n    public long clearCachedFrames() {\n        if (!this.hasCache) {\n            return 0;\n        }\n\n        long cleared = Math.max(0, this.cacheCount.sumAndReset());\n        for (var queue : this.remoteCache) {\n            if (queue != null) {\n                queue.clear();\n            }\n        }\n        this.cacheHead.remove();\n        return cleared;\n    }\n\n    /// Adds the interceptor to the upstream.\n""",
)

replace_once(
    "euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneCache.java",
    """    public final long getLocalCacheCount() {\n        return (long) TOTAL_COUNT.getOpaque(this);\n    }\n\n    public final long getMaxLocalCacheCount() {\n""",
    """    public final long getLocalCacheCount() {\n        return (long) TOTAL_COUNT.getOpaque(this);\n    }\n\n    /**\n     * Clears the fragment-local MPSC cache. This must be invoked by the fragment's pinned consumer\n     * thread after ingress has been frozen.\n     */\n    protected final long clearLocalCacheOnOwnerThread() {\n        if (this.localCache == null) {\n            return 0;\n        }\n\n        long cleared = Math.max(0, (long) TOTAL_COUNT.getOpaque(this));\n        this.localCache.clear();\n        this.cacheTerminal.reset();\n        TOTAL_COUNT.setRelease(this, 0L);\n        return cleared;\n    }\n\n    public final long getMaxLocalCacheCount() {\n""",
)

fragment = "euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneFragment.java"
replace_once(
    fragment,
    """import java.lang.invoke.VarHandle;\nimport java.util.Objects;\nimport java.util.concurrent.atomic.AtomicBoolean;\n""",
    """import java.lang.invoke.VarHandle;\nimport java.util.Arrays;\nimport java.util.Objects;\nimport java.util.concurrent.atomic.AtomicBoolean;\nimport java.util.concurrent.atomic.AtomicLong;\n""",
)
replace_once(
    fragment,
    """    private final AtomicBoolean running = new AtomicBoolean(false);\n    private final PinnedThreadExecutor mainExecutor;\n""",
    """    private final AtomicBoolean running = new AtomicBoolean(false);\n    private final AtomicLong resetRequested = new AtomicLong();\n    private final AtomicLong resetCompleted = new AtomicLong();\n    private final AtomicLong resetCleared = new AtomicLong();\n    private final PinnedThreadExecutor mainExecutor;\n""",
)
replace_once(
    fragment,
    """            while (keepRunning()) {\n                long newUpCount = context.upstream.getCachedUpCount();\n""",
    """            while (keepRunning()) {\n                serviceResetRequest();\n                if (this.benchmarkMode && this.actionPicker.halted()) {\n                    Thread.onSpinWait();\n                    continue;\n                }\n\n                long newUpCount = context.upstream.getCachedUpCount();\n""",
)
replace_once(
    fragment,
    """                if(this.benchmarkMode && this.actionPicker.halted()) {\n                    this.state.reset();\n                    Thread.onSpinWait();\n                    continue;\n                }\n\n""",
    "",
)
replace_once(
    fragment,
    """    private long idleSpin(FlowThread.FlowContext threadContext) {\n        while (keepRunning()) {\n            long upCount = threadContext.upstream.getTrueUpstreamCount();\n""",
    """    private long idleSpin(FlowThread.FlowContext threadContext) {\n        while (keepRunning()) {\n            serviceResetRequest();\n            if (this.benchmarkMode && this.actionPicker.halted()) {\n                return 0;\n            }\n            long upCount = threadContext.upstream.getTrueUpstreamCount();\n""",
)
replace_once(
    fragment,
    """    @Override\n    public ControlPlaneFragment clone(CloneConfig cloneConfig) {\n""",
    """    private void serviceResetRequest() {\n        long requested = this.resetRequested.getAcquire();\n        if (requested <= this.resetCompleted.getOpaque() || this.state == null) {\n            return;\n        }\n\n        long cleared = super.clearLocalCacheOnOwnerThread();\n        this.state.reset();\n        this.resetCleared.setRelease(cleared);\n        this.resetCompleted.setRelease(requested);\n    }\n\n    @Override\n    public long resetForNextTrial(long deadlineNanos) {\n        if (this.state == null) {\n            return 0;\n        }\n        if (!this.running.getAcquire()) {\n            long cleared = super.clearLocalCacheOnOwnerThread();\n            this.state.reset();\n            return cleared;\n        }\n\n        long request = this.resetRequested.incrementAndGet();\n        Thread owner = this.mainThread;\n        if (owner != null) {\n            LockSupport.unpark(owner);\n        }\n        while (this.resetCompleted.getAcquire() < request\n                && this.running.getAcquire() && System.nanoTime() < deadlineNanos) {\n            LockSupport.parkNanos(5_000L);\n        }\n        if (this.resetCompleted.getAcquire() < request) {\n            throw new IllegalStateException(\n                    \"Timed out resetting fragment cache on core \" + this.core);\n        }\n        return this.resetCleared.getAcquire();\n    }\n\n    @Override\n    public ControlPlaneFragment clone(CloneConfig cloneConfig) {\n""",
)
replace_once(
    fragment,
    """            this.batchStart = 0;\n            this.batchSize = 2;\n            this.completed = 0;\n        }\n""",
    """            this.batchStart = 0;\n            this.batchSize = 2;\n            this.completed = 0;\n            this.upstreamCount = 0;\n            this.totalExecutions = 0;\n            Arrays.fill(this.actionInputs, 0.0);\n        }\n""",
)

shard = "euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneShard.java"
replace_once(
    shard,
    """    public boolean isStarted() {\n""",
    """    long resetForNextTrial(long deadlineNanos) {\n        if (!this.started.getAcquire()) {\n            return 0;\n        }\n        while (this.rebalancing.getAcquire() && System.nanoTime() < deadlineNanos) {\n            Thread.onSpinWait();\n        }\n        if (this.rebalancing.getAcquire()) {\n            throw new IllegalStateException(\n                    \"Timed out waiting for shard rebalance before trial reset: \" + this.shardName);\n        }\n\n        LatticeVertex distributor = this.coreDistributor.getAcquire();\n        if (distributor == null) {\n            return 0;\n        }\n\n        distributor.setDrain(true);\n        CloneableObject[] activeClones = this.clones.getAcquire();\n        for (CloneableObject clone : activeClones) {\n            if (clone != null) {\n                clone.setDrainMode(true);\n            }\n        }\n\n        long cleared = distributor.clearCachedFrames();\n        try {\n            for (CloneableObject clone : activeClones) {\n                if (clone != null) {\n                    cleared += clone.resetForNextTrial(deadlineNanos);\n                }\n            }\n            return cleared;\n        } finally {\n            for (CloneableObject clone : activeClones) {\n                if (clone != null) {\n                    clone.setDrainMode(false);\n                }\n            }\n            distributor.setDrain(false);\n        }\n    }\n\n    public boolean isStarted() {\n""",
)

lattice = "euhedral-core/src/main/java/io/euhedral_execution/core/control_plane/ControlPlaneLattice.java"
replace_once(
    lattice,
    """    final AtomicBoolean rebalancing = new AtomicBoolean(false);\n\n    final ControlPlaneShard[] shards;\n""",
    """    final AtomicBoolean rebalancing = new AtomicBoolean(false);\n    final AtomicBoolean resetting = new AtomicBoolean(false);\n\n    final ControlPlaneShard[] shards;\n""",
)
replace_once(
    lattice,
    """    void update(HardwareUtilization utilization) {\n        int nextVersion = this.topology.getGlobalVersion();\n""",
    """    void update(HardwareUtilization utilization) {\n        if (this.resetting.getAcquire()) {\n            return;\n        }\n        int nextVersion = this.topology.getGlobalVersion();\n""",
)
replace_once(
    lattice,
    """    public int getActiveWorkers() {\n""",
    """    /**\n     * Freezes ingest and clears all socket-distributor and fragment-local caches before another\n     * benchmark policy is activated. Ingest sources should be paused before calling this method.\n     */\n    public CacheReset resetForNextTrial(Duration timeout) {\n        Objects.requireNonNull(timeout);\n        if (timeout.isZero() || timeout.isNegative()) {\n            throw new IllegalArgumentException(\"Reset timeout must be positive\");\n        }\n        if (this.closed.getAcquire()) {\n            throw new IllegalStateException(\"Cannot reset a closed ControlPlaneLattice\");\n        }\n        if (!this.started.getAcquire()) {\n            return new CacheReset(0, 0);\n        }\n        if (!this.resetting.compareAndSet(false, true)) {\n            throw new IllegalStateException(\"A lattice reset is already in progress\");\n        }\n\n        long deadline = System.nanoTime() + timeout.toNanos();\n        LatticeVertex controller = this.ingestController.getAcquire();\n        try {\n            while (this.rebalancing.getAcquire() && System.nanoTime() < deadline) {\n                LockSupport.parkNanos(5_000L);\n            }\n            if (this.rebalancing.getAcquire()) {\n                throw new IllegalStateException(\n                        \"Timed out waiting for global rebalance before trial reset\");\n            }\n\n            if (controller != null) {\n                controller.setDrain(true);\n            }\n            long cleared = controller == null ? 0 : controller.clearCachedFrames();\n            for (ControlPlaneShard shard : this.shards) {\n                if (shard != null) {\n                    cleared += shard.resetForNextTrial(deadline);\n                }\n            }\n            return new CacheReset(cleared, getActiveWorkers());\n        } finally {\n            if (controller != null) {\n                controller.setDrain(false);\n            }\n            this.resetting.setRelease(false);\n        }\n    }\n\n    public int getActiveWorkers() {\n""",
)
replace_once(
    lattice,
    """    /// Whether all queues are empty and all in-progress work is completed for all CPUs managed by\n""",
    """    public record CacheReset(long clearedFrames, int activeWorkers) {\n    }\n\n    /// Whether all queues are empty and all in-progress work is completed for all CPUs managed by\n""",
)
