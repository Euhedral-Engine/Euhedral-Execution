package io.euhedral_execution.core.control_plane;

import io.euhedral_execution.core.config.CloneConfig;
import io.euhedral_execution.core.config.FragmentActionPicker;
import io.euhedral_execution.core.config.FragmentActionPicker.Action;
import io.euhedral_execution.core.config.FragmentConfig;
import io.euhedral_execution.core.flow_control.LatticeHotSource;
import io.euhedral_execution.core.frames.AbstractFrame;
import io.euhedral_execution.core.generics.LatticeSource;
import io.euhedral_execution.core.internal.Constants;
import io.euhedral_execution.core.metrics.ExecutionMetrics;
import io.euhedral_execution.core.utils.FlowRecorder;
import io.euhedral_execution.core.utils.FlowThread;
import io.euhedral_execution.core.utils.MathFunctions;
import io.euhedral_execution.hardware_utils.PinnedThreadExecutor;
import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuCacheLayout;
import io.euhedral_execution.hardware_utils.SystemInfo.CpuInfo;
import io.euhedral_execution.hardware_utils.ThreadTools;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CoreSnapshot;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.CpuSnapshot;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The core of Euhedral Core
///
/// `ControlPlaneFragment` is the control loop that sits between ingress and execution. It
/// follows a fixed request and cache-drain order in normal operation while adapting its bounded
/// batch size from observed throughput. Benchmark mode can instead evaluate action-picker vectors
/// without changing the production path.
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
    final FragmentActionPicker actionPicker;
    final LatticeHotSource outputStream;
    private final Logger logger;
    private final ExecutionMetrics metrics;

    @Getter
    private final FragmentConfig config;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong resetRequested = new AtomicLong();
    private final AtomicLong resetCompleted = new AtomicLong();
    private final AtomicLong resetCleared = new AtomicLong();
    private final PinnedThreadExecutor mainExecutor;
    private final CycleState state;
    boolean drainMode = false;
    CoreSnapshot coreSnapshot = null;
    private volatile long adaptiveBatchCap;
    private volatile long lastAcceptedTimestampNs;

    private volatile Thread mainThread;

    public ControlPlaneFragment(@NonNull FragmentConfig config) {
        super(config.cacheConfig());
        this.config = config;
        this.benchmarkMode = config.benchmarkMode();
        this.actionPicker = config.actionPicker();

        if (config.cloneConfig() == null) {
            this.socket = -1;
            this.core = -1;
            this.cpu = -1;
            this.logger = LoggerFactory.getLogger(Constants.getLoggerName(ControlPlaneFragment.class));
            this.state = null;
            this.mainExecutor = null;
            this.isPCore = false;
            this.metrics = null;
            this.outputStream = null;
            this.adaptiveBatchCap = 2L;
            this.lastAcceptedTimestampNs = 0L;
        } else {
            String name = config.cloneConfig().shardName() + "-Worker-"
                    + config.cloneConfig().coreId();
            this.logger = LoggerFactory.getLogger(Constants.getLoggerName(name));

            int[] cpus = config.cloneConfig().getCpuSet();
            this.cpu = cpus[0];

            CpuInfo info = SystemInfo.getCpuInfo(this.cpu);
            this.socket = info.socket();
            this.core = info.core();

            this.state = new CycleState();

            this.mainExecutor = PinnedThreadExecutor.getOrSetIfAbsent(
                    FlowThread.getFactory(), this.cpu, name, Thread.MAX_PRIORITY, false);

            CpuCacheLayout layout = SystemInfo.getCacheLayout(this.cpu);
            this.isPCore = SystemInfo.getCoreInfo(
                            SystemInfo.getCpuInfo(layout.cpu()).core())
                    .pCore();

            this.outputStream = new LatticeHotSource();

            this.metrics = new ExecutionMetrics(config);
            long maxBatch = config.maxBatchSize();
            long quota = super.getFrameQuota();
            this.adaptiveBatchCap = Math.max(2L, Math.min(maxBatch, quota));
            this.lastAcceptedTimestampNs = 0L;
        }
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
                if (this.core != origin.core()) {
                    this.logger.warn(
                            "Attempted to pin to Core: {} CPU: {} but was assigned: {}", this.core, this.cpu, origin);
                } else {
                    this.logger.debug("Pinned to Core {} CPU {} P-Core: {}", this.core, this.cpu, this.isPCore);
                }
                ThreadTools.setTimerResolution(1);
                super.register();
                this.mainThread = Thread.currentThread();

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

            while (keepRunning()) {
                serviceResetRequest();
                if (this.benchmarkMode && this.actionPicker.halted()) {
                    Thread.onSpinWait();
                    continue;
                }

                long newUpCount = context.upstream.getCachedUpCount();
                if (this.state.upstreamCount != newUpCount && newUpCount > 0) {
                    this.state.upstreamCount = newUpCount;
                    GlobalState.resetThroughput(this.socket, this.cpu);
                } else if (this.state.upstreamCount != newUpCount || this.state.upstreamCount == 0) {
                    this.state.upstreamCount = 0;
                    this.state.reset();
                    this.state.upstreamCount = idleSpin(context);
                }

                long limit = this.state.batchSize - this.state.completed;
                long processed = 0;
                if (this.benchmarkMode) {
                    double maxCacheInv = 1.0 / Math.max(1.0, super.getUpstreamCacheCapacity());
                    this.state.actionInputs[0] = this.state.completed;
                    this.state.actionInputs[1] = this.state.batchSize;
                    this.state.actionInputs[2] = this.state.serviceTimeRecorder.averageUnitsOverTime();
                    this.state.actionInputs[3] = this.state.serviceTimeRecorder.unitsOverTimeCV();
                    this.state.actionInputs[4] =
                            (double) context.upstream.getTrueUpstreamCount() / Math.max(1, super.getThreadCount());
                    this.state.actionInputs[5] = super.getUpstreamCacheCount() * maxCacheInv;
                    this.actionPicker.normalize(this.state.actionInputs);
                }

                long start = System.nanoTime();
                boolean request =
                        !this.benchmarkMode || this.actionPicker.performAction(Action.REQUEST, this.state.actionInputs);
                if (request) {
                    super.request(context);
                }
                if (limit > 0 && super.getLocalCacheCount() > 0) {
                    long count = localCacheExecute(limit);
                    processed += count;
                    limit -= count;
                }
                boolean executeRemoteCache = !this.benchmarkMode
                        || this.actionPicker.performAction(Action.REMOTE_CACHE_EXECUTE, this.state.actionInputs);
                if (limit > 0 && executeRemoteCache) {
                    long count = remoteCacheExecute(limit);
                    processed += count;
                    limit -= count;
                }
                boolean executeRemote = !this.benchmarkMode
                        || this.actionPicker.performAction(Action.REMOTE_EXECUTE, this.state.actionInputs);
                if (limit > 0 && executeRemote) {
                    long count = remoteExecute(context, limit);
                    processed += count;
                }
                this.state.completed += processed;
                this.state.totalExecutions += processed;
                long end = System.nanoTime();
                if (processed > 0) {
                    updateLimits(end, end - start, processed);
                }

                boolean park;
                if (this.benchmarkMode) {
                    this.state.actionInputs[0] = this.state.completed;
                    park = this.actionPicker.performAction(Action.SLEEP, this.state.actionInputs);
                } else {
                    park = processed == 0;
                }
                if (park) {
                    LockSupport.parkNanos(20_000);
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

    /// Records a productive iteration and updates the owner thread's bounded batch controller.
    /// `nowNs` is the monotonic completion timestamp, `elapsedNs` is the iteration duration, and
    /// `processed` is the positive number of frames completed during that interval.
    private void updateLimits(long nowNs, long elapsedNs, long processed) {
        long serviceTime = Math.max(1L, elapsedNs / processed);
        this.state.serviceTimeRecorder.recordUnits(nowNs, serviceTime);
        this.state.throughputRecorder.recordUnits(nowNs, processed);

        if (this.state.completed < this.state.batchSize) {
            return;
        }
        this.state.completed = 0;

        this.state.batchRecorder.recordUnits(nowNs, this.state.batchSize);
        if (this.state.batchStart == 0) {
            this.state.batchStart = nowNs;
            return;
        }

        if (this.config.registry() != null) {
            double throughput = this.state.throughputRecorder.averageUnitsOverTime();
            if (Double.isFinite(throughput) && throughput > 0) {
                this.metrics.reportThroughput(throughput);
            }

            double latency = this.state.serviceTimeRecorder.averageUnits();
            if (Double.isFinite(latency) && latency > 0) {
                this.metrics.reportLatency(Math.round(latency));
            }
        }

        long currentBatch = this.state.batchSize;

        FlowRecorder recorder = this.benchmarkMode ? this.state.serviceTimeRecorder : this.state.throughputRecorder;

        double avgThroughput = recorder.averageUnitsOverTime();

        double averageBatch = Math.round(this.state.batchRecorder.averageUnits());
        if (currentBatch == averageBatch) {
            if (this.benchmarkMode) {
                double diffUpper = recorder.getMaxUoT() - avgThroughput;
                double diffLower = avgThroughput - recorder.getMinUoT();
                if (avgThroughput < 0.90) {
                    currentBatch += Math.round(Math.max(Math.sqrt(currentBatch), 1));
                } else if (avgThroughput > 1.0) {
                    currentBatch -= Math.round(Math.max(Math.sqrt(currentBatch), 1));
                } else if (diffLower < diffUpper) {
                    currentBatch++;
                } else {
                    currentBatch--;
                }
            } else if (Double.isFinite(avgThroughput) && avgThroughput > 0) {
                double previousThroughput = this.state.batchThroughput;
                if (previousThroughput > 0 && avgThroughput < previousThroughput * 0.98) {
                    this.state.batchDirection = -this.state.batchDirection;
                }
                long step = Math.round(Math.max(Math.sqrt(currentBatch), 1));
                currentBatch += this.state.batchDirection * step;
                this.state.batchThroughput = avgThroughput;
            }
        }

        currentBatch = MathFunctions.clampLong(currentBatch, 2, this.config.maxBatchSize());
        currentBatch = Math.min(currentBatch, getBatchLimit());

        this.state.batchStart = nowNs;
        this.state.batchSize = currentBatch;
    }

    private long getBatchLimit() {
        long cap = (long) ADAPTIVE_BATCH_CAP.getOpaque(this);
        if (cap < 2L) {
            long maxBatch = this.config.maxBatchSize();
            long quota = super.getFrameQuota();
            cap = Math.max(2L, Math.min(maxBatch, quota));
        }
        return Math.max(2L, cap);
    }

    long getAdaptiveBatchCap() {
        return (long) ADAPTIVE_BATCH_CAP.getOpaque(this);
    }

    private long idleSpin(FlowThread.FlowContext threadContext) {
        while (keepRunning()) {
            serviceResetRequest();
            if (this.benchmarkMode && this.actionPicker.halted()) {
                return 0;
            }
            long upCount = threadContext.upstream.getTrueUpstreamCount();
            if (upCount > 0) {
                return upCount;
            }
            if (super.getLocalCacheCount() > 0 || super.getUpstreamCacheCount() > 0) {
                break;
            }
            LockSupport.parkNanos(20_000L);
        }
        return 0;
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
        final double[] actionInputs = new double[6];

        long batchStart = 0;
        long batchSize = 2;
        long completed = 0;
        long batchDirection = 1;
        double batchThroughput = 0;

        long upstreamCount = 0;
        long totalExecutions = 0;

        void reset() {
            GlobalState.resetThroughput(ControlPlaneFragment.this.socket, ControlPlaneFragment.this.cpu);
            this.batchRecorder.reset();
            this.serviceTimeRecorder.reset();
            this.throughputRecorder.reset();

            this.batchStart = 0;
            this.batchSize = 2;
            this.completed = 0;
            this.batchDirection = 1;
            this.batchThroughput = 0;
            this.upstreamCount = 0;
            this.totalExecutions = 0;
            Arrays.fill(this.actionInputs, 0.0);
        }
    }
}
