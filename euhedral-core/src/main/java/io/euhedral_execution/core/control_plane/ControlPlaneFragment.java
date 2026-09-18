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
import java.util.function.Consumer;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The core of Euhedral Core
///
/// `ControlPlaneFragment` owns the pinned worker loop between ingress and execution. Every cycle
/// drains local work first, selects direct, staged, or idle service, then optionally steals before
/// waiting. Benchmark mode observes that same loop; it does not replace the scheduling policy.
public final class ControlPlaneFragment extends WorkRequester {

    private static final VarHandle ADAPTIVE_BATCH_CAP;

    static {
        try {
            ADAPTIVE_BATCH_CAP =
                    MethodHandles.lookup().findVarHandle(ControlPlaneFragment.class, "adaptiveBatchCap", long.class);
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
    final LatticeHotSource outputStream;
    private final Logger logger;
    private final ExecutionMetrics metrics;

    @Getter
    private final FragmentConfig config;

    private final FragmentObserver observer;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closeRequested = new AtomicBoolean(false);
    private final AtomicLong resetRequested = new AtomicLong();
    private final AtomicLong resetCompleted = new AtomicLong();
    private final AtomicLong resetCleared = new AtomicLong();
    private final PinnedThreadExecutor mainExecutor;
    private final CycleState state;
    private final Consumer<AbstractFrame> executionConsumer;

    @Getter
    private ControlPlaneFragment smtBuddy = null;

    private FragmentDecisionTree controlPolicy;

    private UpstreamQueue upstreamQueue;
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
            installInternalDownstreamMapping(mappings, terminal);
            setDrain(false);
        }
    }

    private ControlPlaneFragment(@NonNull FragmentConfig config, int cpu) {
        super(config.cacheConfig(), cpu, config.smtEnabled());
        this.config = config;
        this.cpu = cpu;
        this.executionConsumer = this::accept;

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
            this.adaptiveBatchCap = initialBatchCap();
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
    public synchronized void start() {
        if (this.closeRequested.getAcquire()) {
            throw new IllegalStateException("Cannot start a closed ControlPlaneFragment");
        }
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

            this.mainExecutor.execute(this::runPinnedWorker);
        }
    }

    private void runPinnedWorker() {
        boolean registered = false;
        this.mainThread = Thread.currentThread();
        try {
            logOwnerPlacement();
            ThreadTools.setTimerResolution(1);
            super.register();
            registered = true;
            this.controlPolicy = new FragmentDecisionTree(this.config.idlePolicy());
            this.state.neighborCursor = this.cpu + 1;
            runOwnerLoop();
        } catch (Exception e) {
            this.logger.error("[CRITICAL] Terminal error encountered in the main loop. Exiting.", e);
        } finally {
            this.initialized = false;
            try {
                if (registered) {
                    super.removeThread();
                }
            } finally {
                FlowThread.clearContext();
                this.mainThread = null;
                this.running.set(false);
            }
        }
    }

    private void logOwnerPlacement() {
        CpuInfo origin = Objects.requireNonNull(ThreadTools.getCpuInfo());
        if (this.core != origin.core()) {
            this.logger.warn("Attempted to pin to Core: {} CPU: {} but was assigned: {}", this.core, this.cpu, origin);
            return;
        }
        this.logger.debug("Pinned to Core {} CPU {} P-Core: {}", this.core, this.cpu, this.isPCore);
    }

    private void runOwnerLoop() {
        FlowThread.FlowContext context = FlowThread.initializeContext();
        context.upstream = getThreadUpstreamQueue();
        this.upstreamQueue = context.upstream;
        this.initialized = true;

        while (keepRunning()) {
            runCycle(context);
        }
    }

    private void runCycle(FlowThread.FlowContext context) {
        this.state.cycleEpoch++;
        handleResetRequest();

        long contention =
                cacheContention(this.config.idlePolicy(), this.controlPolicy, this.upstreamQueue, this.state.nowNs);
        long upstreamCount = this.upstreamQueue.getCachedUpCount();
        this.state.upstreamCount = upstreamCount;
        long localCache = super.getLocalCacheCount();
        if (upstreamCount == 0L && localCache == 0L) {
            LockSupport.parkNanos(FragmentControlConfig.DEFAULT_PARK_NS);
            return;
        }

        long limit = this.state.batchSize - this.state.completed;
        long processed = 0L;
        long executionFrames = 0L;
        long executionElapsedNs = 0L;
        localCache = super.getLocalCacheCount();

        if (limit > 0L && localCache > 0L) {
            long start = System.nanoTime();
            long count = drainLocalCache(limit);
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
        ExecutionPath path =
                selectExecutionPath(contention, productiveHandleCount, upstreamHandleCount, registeredWorkers);

        if (path == ExecutionPath.DIRECT) {
            if (limit > 0L) {
                long start = System.nanoTime();
                long count = pullAvailableUpstream(context, limit);
                long end = System.nanoTime();
                if (count > 0L) {
                    executionFrames += count;
                    executionElapsedNs += end - start;
                    processed += count;
                }
                this.state.nowNs = end;
            }
            if (processed == 0L) {
                super.request(context, this.state.batchSize);
            }
        } else if (path == ExecutionPath.STAGED && limit > 0L && super.getLocalCacheCount() <= this.state.batchSize) {
            super.request(context, this.state.batchSize);
            this.state.nowNs = System.nanoTime();
        }

        if (processed <= 0L) {
            long start = System.nanoTime();
            long count = super.workSteal(this.executionConsumer, limit, this.state.neighborCursor++);
            long end = System.nanoTime();
            if (count > 0L) {
                executionFrames += count;
                executionElapsedNs += end - start;
                processed += count;
            }
            this.state.nowNs = end;
        }

        if (path == ExecutionPath.IDLE && processed == 0L) {
            long parkNanos = this.controlPolicy.updateIdleTimingAndGetParkNanos(
                    this.upstreamQueue, this.state.nowNs, registeredWorkers, productiveHandleCount);
            LockSupport.parkNanos(parkNanos);
            this.state.nowNs = System.nanoTime();
            Thread.yield();
            return;
        }
        if (processed <= 0L) {
            Thread.yield();
            return;
        }

        this.state.completed += processed;
        recordProgress(executionElapsedNs, executionFrames, processed, contention);
        Thread.onSpinWait();
    }

    private ExecutionPath selectExecutionPath(
            long contention, long productiveHandleCount, long upstreamHandleCount, int registeredWorkers) {
        int workerRank = super.getThreadRank(this.cpu);
        if (this.controlPolicy.shouldIdle(contention, productiveHandleCount, registeredWorkers, workerRank)) {
            return ExecutionPath.IDLE;
        }
        return this.controlPolicy.selectExecutionPath(
                productiveHandleCount, upstreamHandleCount, registeredWorkers, contention);
    }

    private long drainLocalCache(long limit) {
        return super.drain(this.executionConsumer, limit);
    }

    private long pullAvailableUpstream(FlowThread.FlowContext context, long limit) {
        return super.upstreamPull(context.upstream, this.executionConsumer, limit);
    }

    /// Selects fixed or adaptive contention aging without changing source-service ownership.
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
            cap = initialBatchCap();
        }
        return cap;
    }

    long getAdaptiveBatchCap() {
        return (long) ADAPTIVE_BATCH_CAP.getOpaque(this);
    }

    @Override
    public synchronized void update(CoreSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        if (this.smtBuddy != null) {
            this.smtBuddy.update(snapshot);
        }

        CpuSnapshot[] cpuSnapshots = snapshot.cpuSnapshots();
        if (cpuSnapshots == null || this.cpu < 0 || this.cpu >= cpuSnapshots.length) {
            return;
        }
        CpuSnapshot cpuSnap = cpuSnapshots[this.cpu];
        if (cpuSnap == null) {
            return;
        }

        double rawPressure = cpuSnap.pressure();
        if (!Double.isFinite(rawPressure)) {
            return;
        }
        double pressure = MathFunctions.clampDouble(rawPressure, 0.0, 1.0);
        long timestampNs = cpuSnap.lastUsageNs();
        if (this.lastAcceptedTimestampNs != 0L && timestampNs <= this.lastAcceptedTimestampNs) {
            return;
        }

        long eligibleMax = initialBatchCap();
        long eligibleMin = 2L;
        long calculatedCap = Math.round(eligibleMax - pressure * (eligibleMax - eligibleMin));
        long newCap = MathFunctions.clampLong(calculatedCap, eligibleMin, eligibleMax);

        this.lastAcceptedTimestampNs = timestampNs;
        ADAPTIVE_BATCH_CAP.setRelease(this, newCap);
        super.update(snapshot);
    }

    private long initialBatchCap() {
        return Math.max(2L, Math.min(this.config.maxBatchSize(), super.getFrameQuota()));
    }

    private boolean keepRunning() {
        return this.running.getOpaque() && !Thread.currentThread().isInterrupted();
    }

    private void handleResetRequest() {
        long requested = this.resetRequested.getAcquire();
        if (this.state == null || requested <= this.resetCompleted.getOpaque()) {
            return;
        }

        long cleared = resetFragmentState(true);
        this.resetCleared.setRelease(cleared);
        this.resetCompleted.setRelease(requested);
    }

    private long resetFragmentState(boolean resetOwnerFlowContext) {
        long cleared = super.clearLocalCacheOnOwnerThread();
        super.resetAdaptiveCacheStateOnOwnerThread();
        this.state.reset();
        if (this.upstreamQueue != null) {
            this.upstreamQueue.resetForNextTrial();
        }
        if (this.controlPolicy != null) {
            this.controlPolicy.reset();
        }
        if (resetOwnerFlowContext) {
            FlowThread.FlowContext context = FlowThread.getContext();
            if (context != null) {
                context.clearCounters();
            }
        }
        ADAPTIVE_BATCH_CAP.setRelease(this, initialBatchCap());
        this.lastAcceptedTimestampNs = 0L;
        return cleared;
    }

    @Override
    public synchronized long reset(long deadlineNanos) {
        if (this.state == null) {
            return (this.smtBuddy == null ? 0 : this.smtBuddy.reset(deadlineNanos));
        }
        if (!this.running.getAcquire()) {
            long cleared = resetFragmentState(false);
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
        super.setDrainMode(value);
        if (this.smtBuddy != null) {
            this.smtBuddy.setDrainMode(value);
        }
    }

    @Override
    public synchronized void close() {
        this.closeRequested.setRelease(true);
        this.running.set(false);
        Thread owner = this.mainThread;
        if (owner != null && owner != Thread.currentThread()) {
            owner.interrupt();
            LockSupport.unpark(owner);
            try {
                owner.join(500L);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        }

        Throwable failure = null;
        if (this.smtBuddy != null) {
            failure = attemptCleanup(failure, this.smtBuddy::close);
        }
        if (this.mainExecutor != null) {
            failure = attemptCleanup(failure, this.mainExecutor::close);
        }
        if (this.metrics != null) {
            failure = attemptCleanup(failure, this.metrics::close);
        }
        failure = attemptCleanup(failure, super::close);
        this.logger.debug("Closed");
        rethrowCleanupFailure(failure);
    }

    private static Throwable attemptCleanup(Throwable failure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable next) {
            if (failure == null) {
                return next;
            }
            if (next != failure) {
                failure.addSuppressed(next);
            }
        }
        return failure;
    }

    private static void rethrowCleanupFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("ControlPlaneFragment cleanup failed", failure);
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

    private static final class CycleState {

        final FlowRecorder serviceTimeRecorder = new FlowRecorder();
        final FlowRecorder throughputRecorder = new FlowRecorder();

        long batchSize = 2;
        long completed = 0;

        long upstreamCount = 0;
        int registeredWorkers = 0;
        long productiveHandleCount = 0;
        int workerRank = -1;

        long cycleEpoch = -1;
        long batchEpoch = 0;

        long nowNs = System.nanoTime();

        int neighborCursor = 0;

        void reset() {
            this.serviceTimeRecorder.reset();
            this.throughputRecorder.reset();

            this.batchSize = 2;
            this.completed = 0;
            this.upstreamCount = 0;
            this.registeredWorkers = 0;
            this.productiveHandleCount = 0;
            this.workerRank = -1;
            this.cycleEpoch = -1L;
            this.batchEpoch = 0L;
            this.nowNs = System.nanoTime();
        }
    }
}
