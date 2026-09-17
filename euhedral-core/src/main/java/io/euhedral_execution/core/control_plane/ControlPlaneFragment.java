package io.euhedral_execution.core.control_plane;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.config.IdlePolicy;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath;
import io.euhedral_execution.core.flow_control.LatticeEdge;
import io.euhedral_execution.core.flow_control.LatticeHotSource;
import io.euhedral_execution.core.flow_control.UpstreamQueue;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.internal.Constants;
import io.euhedral_execution.core.metrics.ExecutionMetrics;
import io.euhedral_execution.core.utils.FlowRecorder;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.core.utils.MathFunctions;
import io.euhedral_execution.core.utils.StopWatch;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CpuSnapshot;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.BitSet;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The core of Euhedral Core
///
/// `ControlPlaneFragment` is the control loop that sits between ingress and execution. Normal mode
/// uses a deterministic availability/body-cost direct/staged policy, while benchmark mode evaluates the
/// existing action-picker vectors on an independent loop.
public final class ControlPlaneFragment extends WorkRequester {

    private static final VarHandle DRAIN;
    private static final VarHandle SNAPSHOT;
    private static final VarHandle ADAPTIVE_BATCH_CAP;
    private static final VarHandle LAST_ACCEPTED_TIMESTAMP_NS;

    static {
        try {
            DRAIN = MethodHandles.lookup().findVarHandle(ControlPlaneFragment.class, "drainMode", boolean.class);
            SNAPSHOT = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "coreSnapshot", CoreSnapshot.class);
            ADAPTIVE_BATCH_CAP =
                    MethodHandles.lookup().findVarHandle(ControlPlaneFragment.class, "adaptiveBatchCap", long.class);
            LAST_ACCEPTED_TIMESTAMP_NS = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "lastAcceptedTimestampNs", long.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static int getPrimaryCpu(FragmentConfig config) {
        Objects.requireNonNull(config);
        if (config.cloneConfig() != null) {
            return config.cloneConfig().effectiveCpus().nextSetBit(0);
        }
        return -1;
    }

    public final int socket;
    public final int core;
    public final int cpu;
    public final boolean isPCore;
    final boolean benchmarkMode;
    final LatticeHotSource outputStream;
    private final Logger logger;
    private final ExecutionMetrics metrics;

    @Getter
    private final FragmentConfig config;

    private final FragmentObserver observer;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong resetRequested = new AtomicLong();
    private final AtomicLong resetCompleted = new AtomicLong();
    private final AtomicLong resetCleared = new AtomicLong();
    private final PinnedThreadExecutor mainExecutor;
    private final CycleState state;

    @Getter
    private ControlPlaneFragment smtBuddy = null;

    private FragmentDecisionTree controlPolicy;

    private UpstreamQueue upstreamQueue;
    boolean drainMode = false;
    CoreSnapshot coreSnapshot = null;
    private volatile long adaptiveBatchCap;
    private volatile long lastAcceptedTimestampNs;

    private volatile Thread mainThread;

    /// Published after owner-local initialization. A successful ready() read acquires the policy,
    /// calibrated thresholds, and upstream queue without making their hot-path accesses volatile.
    private volatile boolean initialized;

    public ControlPlaneFragment(@NonNull FragmentConfig config) {
        this(config, getPrimaryCpu(config));

        if (config.smtEnabled()
                && this.cpu > -1
                && config.cloneConfig().effectiveCpus().cardinality() > 1) {
            this.smtBuddy =
                    new ControlPlaneFragment(config, config.cloneConfig().getCpuSet()[1]);

            BitSet mappings = new BitSet(2);
            mappings.set(0, 2);
            LatticeEdge[] terminal = new LatticeEdge[] {new LatticeEdge(super.drain), new LatticeEdge(super.drain)};
            terminal[0].addDownstream(this.cacheTerminal);
            terminal[1].addDownstream(this.smtBuddy.cacheTerminal);

            setDrain(true);
            super.setDownstreamMapping(mappings, terminal);
            setDrain(false);

            this.smtBuddy.setParent(super.parent);
        }
    }

    private ControlPlaneFragment(@NonNull FragmentConfig config, int cpu) {
        super(config.cacheConfig(), cpu, config.smtEnabled());
        this.config = config;
        this.benchmarkMode = config.benchmarkMode();
        this.cpu = cpu;

        if (config.cloneConfig() == null) {
            this.socket = -1;
            this.core = -1;
            this.observer = null;
            this.logger = LoggerFactory.getLogger(Constants.getLoggerName(ControlPlaneFragment.class));
            this.controlPolicy = null;
            this.state = null;
            this.mainExecutor = null;
            this.isPCore = false;
            this.metrics = null;
            this.outputStream = null;
            this.adaptiveBatchCap = 2L;
        } else {
            String name = config.cloneConfig().shardName() + "-Worker-"
                    + config.cloneConfig().coreId();
            this.logger = LoggerFactory.getLogger(Constants.getLoggerName(name));

            CpuInfo info = SystemInfo.getCpuInfo(this.cpu);
            this.socket = info.socket();
            this.core = info.core();

            if (config.benchmarkMode()) {
                Objects.requireNonNull(config.observer());
                this.observer = config.observer();
            } else {
                this.observer = null;
            }
            this.state = new CycleState();

            this.mainExecutor = PinnedThreadExecutor.getOrSetIfAbsent(
                    FlowThread.getFactory(), this.cpu, name, Thread.MAX_PRIORITY, false);

            CpuCacheLayout layout = SystemInfo.getCacheLayout(this.cpu);
            this.isPCore = SystemInfo.getCoreInfo(
                            SystemInfo.getCpuInfo(layout.cpu()).core())
                    .pCore();

            StopWatch stopWatch = new StopWatch();
            this.outputStream = new LatticeHotSource(ignored -> stopWatch.start(), () -> {
                long elapsed = stopWatch.stop();
                if (elapsed > 0) {
                    this.controlPolicy.recordBodyCost(elapsed);
                }
            });

            this.metrics = new ExecutionMetrics(config);
            long maxBatch = config.maxBatchSize();
            long quota = super.getFrameQuota();
            this.adaptiveBatchCap = Math.max(2L, Math.min(maxBatch, quota));
        }
        this.lastAcceptedTimestampNs = 0L;
    }

    @Override
    public void firstTouch() {
        if (this.smtBuddy != null) {
            this.smtBuddy.firstTouch();
        }
        super.firstTouch();
    }

    @Override
    protected void accept(AbstractFrame frame) {
        this.metrics.addInProgress(1);
        try {
            this.outputStream.accept(frame);
        } finally {
            this.metrics.addInProgress(-1);
        }
    }

    @Override
    public LatticeSource output() {
        return this.outputStream;
    }

    @Override
    public boolean isStarted() {
        return this.running.getAcquire() && (this.smtBuddy == null || this.smtBuddy.isStarted());
    }

    @Override
    public boolean ready() {
        return this.running.getAcquire() && this.initialized && (this.smtBuddy == null || this.smtBuddy.ready());
    }

    @Override
    public void start() {
        if (this.mainExecutor == null) {
            throw new IllegalStateException(
                    "Pinned Executor has not been set. To start this class, it needs to be instantiated with"
                            + " a CloneConfig.");
        }
        if (this.running.compareAndSet(false, true)) {
            if (this.mainExecutor.isShutdown()) {
                this.mainExecutor.start(this.logger.getName(), Thread.MAX_PRIORITY, false);
            }
            if (this.smtBuddy != null) {
                this.smtBuddy.start();
            }

            this.mainExecutor.execute(() -> {
                CpuInfo origin = ThreadTools.getCpuInfo();
                Objects.requireNonNull(origin);
                if (this.core != origin.core()) {
                    this.logger.warn(
                            "Attempted to pin to Core: {} CPU: {} but was assigned: {}", this.core, this.cpu, origin);
                } else {
                    this.logger.debug("Pinned to Core {} CPU {} P-Core: {}", this.core, this.cpu, this.isPCore);
                }
                ThreadTools.setTimerResolution(1);
                super.register();
                this.mainThread = Thread.currentThread();
                this.controlPolicy =
                        new FragmentDecisionTree(this.observer, this.core, this.socket, this.config.idlePolicy());

                try {
                    this.state.neighborCursor = this.cpu + 1;
                    cycle();
                } finally {
                    this.initialized = false;
                    try {
                        super.removeThread();
                    } finally {
                        FlowThread.clearContext();
                        this.mainThread = null;
                    }
                }
            });
        }
    }

    private void cycle() {
        try {
            FlowThread.FlowContext context = FlowThread.initializeContext();
            context.upstream = getThreadUpstreamQueue();
            this.upstreamQueue = context.upstream;

            this.initialized = true;
            while (keepRunning()) {
                this.state.cycleEpoch++;
                handleResetRequest();

                long contention = cacheContention(
                        this.config.idlePolicy(), this.controlPolicy, this.upstreamQueue, this.state.nowNs);
                long newUpCount = this.upstreamQueue.getCachedUpCount();
                if (this.state.upstreamCount != newUpCount) {
                    this.state.upstreamCount = newUpCount;
                }

                long localCache = super.getLocalCacheCount();

                if (newUpCount == 0 && localCache == 0) {
                    LockSupport.parkNanos(FragmentControlConfig.DEFAULT_PARK_NS);
                    continue;
                }

                long limit = this.state.batchSize - this.state.completed;
                long processed = 0L;
                long executionFrames = 0L;
                long executionElapsedNs = 0L;
                localCache = super.getLocalCacheCount();

                if (limit > 0L && localCache > 0L) {
                    long start = System.nanoTime();
                    long count = localCacheExecute(limit);
                    long end = System.nanoTime();
                    if (count > 0L) {
                        executionFrames += count;
                        executionElapsedNs += end - start;
                        processed += count;
                        limit -= count;
                    }
                    this.state.nowNs = end;
                }

                long productiveHandleCount = this.upstreamQueue.getProductiveHandleCount();
                long upstreamHandleCount = this.upstreamQueue.getCachedUpCount();
                int registeredWorkers = this.state.registeredWorkers;
                int workerRank = super.getThreadRank(this.cpu);
                ExecutionPath path;
                if (this.controlPolicy.shouldIdle(contention, productiveHandleCount, registeredWorkers, workerRank)) {
                    path = ExecutionPath.IDLE;
                } else {
                    path = this.controlPolicy.executionPath(
                            this.state.cycleEpoch,
                            this.state.batchEpoch,
                            productiveHandleCount,
                            upstreamHandleCount,
                            registeredWorkers,
                            contention);
                }

                if (path == ExecutionPath.DIRECT) {
                    if (limit > 0L) {
                        long start = System.nanoTime();
                        long count = remoteExecute(context, limit);
                        long end = System.nanoTime();
                        if (count > 0L) {
                            executionFrames += count;
                            executionElapsedNs += end - start;
                            processed += count;
                        }
                        this.state.nowNs = end;
                    }
                    if (processed == 0) {
                        super.request(context, this.state.batchSize);
                    }
                } else if (path == ExecutionPath.STAGED
                        && limit > 0L
                        && super.getLocalCacheCount() <= this.state.batchSize) {
                    super.request(context, this.state.batchSize);
                    this.state.nowNs = System.nanoTime();
                }
                if (processed <= 0) {
                    long start = System.nanoTime();
                    long count = super.workSteal(this.outputStream, limit, this.state.neighborCursor++);
                    long end = System.nanoTime();
                    if (count > 0L) {
                        executionFrames += count;
                        executionElapsedNs += end - start;
                        processed += count;
                    }
                    this.state.nowNs = end;
                }
                if (path == ExecutionPath.IDLE && processed == 0L) {
                    this.controlPolicy.idle(
                            this.upstreamQueue, this.state.nowNs, registeredWorkers, productiveHandleCount);
                    this.state.nowNs = System.nanoTime();
                    Thread.yield();
                    continue;
                }
                if (processed <= 0L) {
                    Thread.yield();
                    continue;
                }

                this.state.completed += processed;
                recordProgress(executionElapsedNs, executionFrames, processed, contention);
                Thread.onSpinWait();
            }
        } catch (Exception e) {
            this.logger.error("[CRITICAL] Terminal error encountered in the main loop. Exiting.", e);
        } finally {
            this.running.set(false);
        }
    }

    private long localCacheExecute(long limit) {
        return super.drain(this.outputStream, limit);
    }

    private long remoteExecute(FlowThread.FlowContext context, long limit) {
        return super.upstreamPull(context.upstream, this.outputStream, limit);
    }

    // Owner-thread helpers preserve the fixed production bypass. CACHE remains the hybrid mode;
    // this policy only chooses the local idle interval and prospective evidence decay.
    static long cacheContention(IdlePolicy timing, FragmentDecisionTree policy, UpstreamQueue upstream, long now) {
        return timing.function() == null
                ? upstream.getEffectiveContention(now, policy.contentionHalfLifeNanos())
                : upstream.getAdaptiveContention(now, policy.contentionHalfLifeNanos());
    }

    /// Records loop execution telemetry and advances policy only at a batch boundary.
    private void recordProgress(long executionElapsedNs, long executionFrames, long processed, long contention) {
        this.controlPolicy.recordExecution(executionElapsedNs, executionFrames);
        if (executionElapsedNs > 0L && executionFrames > 0L) {
            long serviceTime = Math.max(1L, executionElapsedNs / executionFrames);
            this.state.serviceTimeRecorder.recordUnits(this.state.nowNs, serviceTime);
        }
        this.state.throughputRecorder.recordUnits(this.state.nowNs, processed);

        if (this.state.completed < this.state.batchSize) {
            if (this.config.benchmarkMode()) {
                this.observer.batchProgressState(
                        this.core,
                        this.socket,
                        this.state.cycleEpoch,
                        this.state.batchEpoch,
                        this.state.upstreamCount,
                        this.state.registeredWorkers,
                        this.state.productiveHandleCount,
                        this.state.workerRank,
                        contention,
                        this.state.serviceTimeRecorder.averageUnits());
            }
            return;
        }

        int registeredWorkers = super.getThreadCount();
        int workerRank = super.getThreadRank(this.cpu);
        long productiveHandleCount = this.state.productiveHandleCount;

        if (this.config.benchmarkMode()) {
            productiveHandleCount = this.upstreamQueue.getProductiveHandleCount();
            this.observer.batchCompleteState(
                    this.core,
                    this.socket,
                    this.state.cycleEpoch,
                    this.state.batchEpoch,
                    this.state.upstreamCount,
                    registeredWorkers,
                    productiveHandleCount,
                    workerRank,
                    contention,
                    this.state.serviceTimeRecorder.averageUnits(),
                    this.state.throughputRecorder.averageUnitsOverTime());
            this.state.batchEpoch++;
        }

        this.state.completed = 0L;
        this.state.batchSize = this.controlPolicy.completeBatch(getBatchLimit());
        this.state.registeredWorkers = registeredWorkers;
        if (this.config.benchmarkMode()) {
            this.state.productiveHandleCount = productiveHandleCount;
        }
        this.state.workerRank = workerRank;
        reportMetrics();
    }

    /// Publishes telemetry from the existing service and throughput recorders when configured.
    private void reportMetrics() {
        if (this.config.registry() == null) {
            return;
        }
        double throughput = this.state.throughputRecorder.averageUnitsOverTime();
        if (Double.isFinite(throughput) && throughput > 0) {
            this.metrics.reportThroughput(throughput);
        }

        double latency = this.state.serviceTimeRecorder.averageUnits();
        if (Double.isFinite(latency) && latency > 0) {
            this.metrics.reportLatency(Math.round(latency));
        }
    }

    private long getBatchLimit() {
        long cap = (long) ADAPTIVE_BATCH_CAP.getOpaque(this);
        if (cap < 2L) {
            long maxBatch = this.config.maxBatchSize();
            long quota = super.getFrameQuota();
            cap = Math.max(2L, Math.min(maxBatch, quota));
        }
        return cap;
    }

    long getAdaptiveBatchCap() {
        return (long) ADAPTIVE_BATCH_CAP.getOpaque(this);
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        if (snapshot == null || snapshot.cpuSnapshots() == null) {
            return;
        }
        if (this.smtBuddy != null) {
            this.smtBuddy.update(snapshot);
        }

        if (this.cpu < 0 || this.cpu >= snapshot.cpuSnapshots().length) {
            return;
        }
        CpuSnapshot cpuSnap = snapshot.cpuSnapshots()[this.cpu];
        if (cpuSnap == null) {
            return;
        }

        double rawPressure = cpuSnap.pressure();
        if (!Double.isFinite(rawPressure)) {
            return;
        }
        double pressure = MathFunctions.clampDouble(rawPressure, 0.0, 1.0);
        long timestampNs = cpuSnap.lastUsageNs();

        long lastAccepted = (long) LAST_ACCEPTED_TIMESTAMP_NS.getAcquire(this);
        while (timestampNs > lastAccepted || lastAccepted == 0L) {
            if (LAST_ACCEPTED_TIMESTAMP_NS.compareAndSet(this, lastAccepted, timestampNs)) {
                long maxBatch = this.config.maxBatchSize();
                long quota = super.getFrameQuota();
                long eligibleMax = Math.max(2L, Math.min(maxBatch, quota));
                long eligibleMin = 2L;

                long calculatedCap = Math.round(eligibleMax - pressure * (eligibleMax - eligibleMin));
                long newCap = MathFunctions.clampLong(calculatedCap, eligibleMin, eligibleMax);

                ADAPTIVE_BATCH_CAP.setRelease(this, newCap);
                SNAPSHOT.setOpaque(this, snapshot);
                super.update(snapshot);
                return;
            }
            lastAccepted = (long) LAST_ACCEPTED_TIMESTAMP_NS.getAcquire(this);
        }
    }

    private boolean keepRunning() {
        return this.running.getOpaque() && !Thread.currentThread().isInterrupted();
    }

    private void handleResetRequest() {
        long requested = this.resetRequested.getAcquire();
        if (this.state == null || requested <= this.resetCompleted.getOpaque()) {
            return;
        }

        long cleared = super.clearLocalCacheOnOwnerThread();
        super.resetAdaptiveCacheStateOnOwnerThread();
        this.state.reset();
        FlowThread.FlowContext context = FlowThread.getContext();
        if (context != null) {
            context.clearCounters();
        }
        long maxBatch = this.config.maxBatchSize();
        long quota = super.getFrameQuota();
        ADAPTIVE_BATCH_CAP.setRelease(this, Math.max(2L, Math.min(maxBatch, quota)));
        LAST_ACCEPTED_TIMESTAMP_NS.setRelease(this, 0L);
        this.resetCleared.setRelease(cleared);
        this.resetCompleted.setRelease(requested);
    }

    @Override
    public long reset(long deadlineNanos) {
        if (this.state == null) {
            return (this.smtBuddy == null ? 0 : this.smtBuddy.reset(deadlineNanos));
        }
        if (!this.running.getAcquire()) {
            long cleared = super.clearLocalCacheOnOwnerThread();
            this.state.reset();
            return cleared + (this.smtBuddy == null ? 0 : this.smtBuddy.reset(deadlineNanos));
        }

        long request = this.resetRequested.incrementAndGet();
        Thread owner = this.mainThread;
        if (owner != null) {
            LockSupport.unpark(owner);
        }
        while (this.resetCompleted.getAcquire() < request
                && this.running.getAcquire()
                && System.nanoTime() < deadlineNanos) {
            LockSupport.parkNanos(5_000L);
        }
        if (this.resetCompleted.getAcquire() < request) {
            throw new IllegalStateException("Timed out resetting fragment cache on core " + this.core);
        }
        return this.resetCleared.getAcquire() + (this.smtBuddy == null ? 0 : this.smtBuddy.reset(deadlineNanos));
    }

    @Override
    public ControlPlaneFragment clone(CloneConfig cloneConfig) {
        return new ControlPlaneFragment(this.config.clone(cloneConfig));
    }

    @Override
    public boolean isDrained() {
        return super.isDrained()
                && this.metrics.getInProgress() == 0
                && (this.smtBuddy == null || this.smtBuddy.isDrained());
    }

    @Override
    public void setDrainMode(boolean value) {
        DRAIN.setRelease(this, value);
        super.setDrainMode(value);
        if (this.smtBuddy != null) {
            this.smtBuddy.setDrainMode(value);
        }
    }

    @Override
    public void close() {
        if (this.running.compareAndSet(true, false)) {
            if (this.mainThread != null) {
                try {
                    this.mainThread.interrupt();
                    LockSupport.unpark(this.mainThread);
                    this.mainThread.interrupt();
                    this.mainThread.join(500);
                } catch (Exception ignored) {
                    // Closing. Ignore interrupts
                }
                this.mainThread = null;
            }
            dumpLocks();
            this.metrics.close();
        }
        super.close();
        this.logger.debug("Closed");
        if (this.smtBuddy != null) {
            this.smtBuddy.close();
        }
    }

    @Override
    public void dumpLocks() {
        if (this.mainExecutor != null) {
            this.mainExecutor.close();
        }
        if (this.smtBuddy != null) {
            this.smtBuddy.dumpLocks();
        }
    }

    private class CycleState {

        final FlowRecorder batchRecorder = new FlowRecorder();
        final FlowRecorder serviceTimeRecorder = new FlowRecorder();
        final FlowRecorder throughputRecorder = new FlowRecorder();

        long batchSize = 2;
        long completed = 0;

        long upstreamCount = 0;
        int registeredWorkers = 0;
        long productiveHandleCount = 0;
        int workerRank = -1;
        long productivityExclusionCount;

        long cycleEpoch = -1;
        long batchEpoch = 0;
        long lastContentionObservationCount = 0L;
        long lastContentionObservationCycle = -1L;
        long consecutiveIdleDecisions = 0L;

        long nowNs = System.nanoTime();

        int neighborCursor = 0;

        void reset() {
            this.batchRecorder.reset();
            this.serviceTimeRecorder.reset();
            this.throughputRecorder.reset();

            this.batchSize = 2;
            this.completed = 0;
            this.upstreamCount = 0;
            this.registeredWorkers = 0;
            this.productiveHandleCount = 0;
            this.workerRank = -1;
            this.productivityExclusionCount = 0L;
            this.cycleEpoch = -1L;
            this.batchEpoch = 0L;
            this.lastContentionObservationCount = 0L;
            this.lastContentionObservationCycle = -1L;
            this.consecutiveIdleDecisions = 0L;
            if (ControlPlaneFragment.this.upstreamQueue != null) {
                ControlPlaneFragment.this.upstreamQueue.resetForNextTrial();
            }
            if (ControlPlaneFragment.this.controlPolicy != null) {
                ControlPlaneFragment.this.controlPolicy.reset();
            }
            this.nowNs = System.nanoTime();
        }
    }
}
