package io.euhedral_execution.core.control_plane;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath;
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

    private FragmentDecisionTree controlPolicy;

    private UpstreamQueue upstreamQueue;
    boolean drainMode = false;
    CoreSnapshot coreSnapshot = null;
    private volatile long adaptiveBatchCap;
    private volatile long lastAcceptedTimestampNs;

    private volatile Thread mainThread;

    public ControlPlaneFragment(@NonNull FragmentConfig config) {
        super(config.cacheConfig());
        this.config = config;
        this.benchmarkMode = config.benchmarkMode();

        if (config.cloneConfig() == null) {
            this.socket = -1;
            this.core = -1;
            this.cpu = -1;
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

            int[] cpus = config.cloneConfig().getCpuSet();
            this.cpu = cpus[0];

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
                    if (config.benchmarkMode()) {
                        this.observer.rawBodyCost(
                                this.core, this.socket, this.state.cycleEpoch, this.state.batchEpoch, elapsed);
                    }
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
        return this.running.getAcquire();
    }

    @Override
    public boolean ready() {
        return this.running.getAcquire() && this.mainThread != null;
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
                        new FragmentDecisionTree(this.config.decisionWeights(), this.observer, this.core, this.socket);

                try {
                    cycle();
                } finally {
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
            while (keepRunning()) {
                this.state.cycleEpoch++;
                serviceResetRequest();

                long newUpCount = this.upstreamQueue.getCachedUpCount();
                if (this.state.upstreamCount != newUpCount) {
                    this.state.upstreamCount = newUpCount;
                }

                long localCache = super.getLocalCacheCount();

                if (this.config.benchmarkMode()) {
                    this.observer.cycleStartState(
                            this.core,
                            this.socket,
                            this.state.cycleEpoch,
                            this.state.batchEpoch,
                            this.state.completed,
                            this.state.batchSize,
                            newUpCount,
                            this.state.registeredWorkers,
                            this.state.productiveHandleCount,
                            this.state.workerRank,
                            upstreamQueue.getContention(),
                            this.state.throughputRecorder.averageUnitsOverTime());
                }

                if (localCache == 0L) {
                    this.controlPolicy.idle(
                            this.state.cycleEpoch,
                            this.state.batchEpoch,
                            this.state.upstreamCount,
                            this.state.registeredWorkers,
                            this.state.workerRank,
                            this.upstreamQueue.getContention());
                    localCache = super.getLocalCacheCount();
                }

                if (newUpCount == 0 && localCache == 0 && super.getUpstreamCacheCount() == 0) {
                    this.state.upstreamCount = this.upstreamQueue.getTrueUpstreamCount();
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
                }

                ExecutionPath path = this.controlPolicy.executionPath(
                        this.state.cycleEpoch,
                        this.state.batchEpoch,
                        this.upstreamQueue.getCachedUpCount(),
                        this.state.registeredWorkers,
                        this.upstreamQueue.getContention());
                if (path == ExecutionPath.DIRECT) {
                    if (limit > 0L) {
                        long start = System.nanoTime();
                        long count = remoteCacheExecute(limit);
                        long end = System.nanoTime();
                        if (count > 0L) {
                            executionFrames += count;
                            executionElapsedNs += end - start;
                            processed += count;
                            limit -= count;
                        }
                    }
                    if (limit > 0L) {
                        long start = System.nanoTime();
                        long count = remoteExecute(context, limit);
                        long end = System.nanoTime();
                        if (count > 0L) {
                            executionFrames += count;
                            executionElapsedNs += end - start;
                            processed += count;
                        }
                    }
                    if (processed == 0L) {
                        super.requestAndPull(context, this.state.batchSize);
                    }
                } else if (path == ExecutionPath.STAGED) {
                    if (limit > 0L) {
                        super.request(context);
                    }
                    if (limit > 0L && super.getLocalCacheCount() > 0L) {
                        long start = System.nanoTime();
                        long count = localCacheExecute(limit);
                        long end = System.nanoTime();
                        if (count > 0L) {
                            executionFrames += count;
                            executionElapsedNs += end - start;
                            processed += count;
                            limit -= count;
                        }
                    }
                    if (limit > 0L) {
                        long start = System.nanoTime();
                        long count = remoteCacheExecute(limit);
                        long end = System.nanoTime();
                        if (count > 0L) {
                            executionFrames += count;
                            executionElapsedNs += end - start;
                            processed += count;
                            limit -= count;
                        }
                    }
                    if (limit > 0L) {
                        long start = System.nanoTime();
                        long count = remoteExecute(context, limit);
                        long end = System.nanoTime();
                        if (count > 0L) {
                            executionFrames += count;
                            executionElapsedNs += end - start;
                            processed += count;
                        }
                    }
                } else {
                    continue;
                }

                this.state.completed += processed;
                long nowNs = System.nanoTime();
                if (processed > 0L) {
                    recordProgress(nowNs, executionElapsedNs, executionFrames, processed);
                    this.controlPolicy.recordProgress();
                    Thread.onSpinWait();
                } else if (this.controlPolicy.missRequiresPark()) {
                    LockSupport.parkNanos(1_000L);
                } else {
                    Thread.onSpinWait();
                }
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

    private long remoteCacheExecute(long limit) {
        return super.pull(this.outputStream, NO_STOP, limit);
    }

    private long remoteExecute(FlowThread.FlowContext context, long limit) {
        return super.upstreamPull(context.upstream, this.outputStream, limit);
    }

    /// Records loop execution telemetry and advances policy only at a batch boundary.
    private void recordProgress(long nowNs, long executionElapsedNs, long executionFrames, long processed) {
        this.controlPolicy.recordExecution(executionElapsedNs, executionFrames);
        if (executionElapsedNs > 0L && executionFrames > 0L) {
            long serviceTime = Math.max(1L, executionElapsedNs / executionFrames);
            this.state.serviceTimeRecorder.recordUnits(nowNs, serviceTime);
        }
        this.state.throughputRecorder.recordUnits(nowNs, processed);

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
                        this.upstreamQueue.getContention(),
                        this.state.serviceTimeRecorder.averageUnits());
            }
            return;
        }

        int registeredWorkers = super.getThreadCount();
        int workerRank = super.getThreadRank(this.core);
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
                    upstreamQueue.getContention(),
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
        int cpuId = this.cpu;
        if (cpuId < 0 || cpuId >= snapshot.cpuSnapshots().length) {
            return;
        }
        CpuSnapshot cpuSnap = snapshot.cpuSnapshots()[cpuId];
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

    private void serviceResetRequest() {
        long requested = this.resetRequested.getAcquire();
        if (this.state == null || requested <= this.resetCompleted.getOpaque()) {
            return;
        }

        long cleared = super.clearLocalCacheOnOwnerThread();
        this.state.reset();
        this.resetCleared.setRelease(cleared);
        this.resetCompleted.setRelease(requested);
    }

    @Override
    public long reset(long deadlineNanos) {
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
                && this.running.getAcquire()
                && System.nanoTime() < deadlineNanos) {
            LockSupport.parkNanos(5_000L);
        }
        if (this.resetCompleted.getAcquire() < request) {
            throw new IllegalStateException("Timed out resetting fragment cache on core " + this.core);
        }
        return this.resetCleared.getAcquire();
    }

    @Override
    public ControlPlaneFragment clone(CloneConfig cloneConfig) {
        return new ControlPlaneFragment(this.config.clone(cloneConfig));
    }

    @Override
    public boolean isDrained() {
        return super.isDrained() && this.metrics.getInProgress() == 0;
    }

    @Override
    public void setDrainMode(boolean value) {
        DRAIN.setRelease(this, value);
        super.setDrainMode(value);
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
    }

    @Override
    public void dumpLocks() {
        if (this.mainExecutor != null) {
            this.mainExecutor.close();
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

        long cycleEpoch = -1;
        long batchEpoch = 0;

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
            if (ControlPlaneFragment.this.upstreamQueue != null) {
                ControlPlaneFragment.this.upstreamQueue.resetAcquireContention();
            }
            if (ControlPlaneFragment.this.controlPolicy != null) {
                ControlPlaneFragment.this.controlPolicy.reset();
            }
        }
    }
}
