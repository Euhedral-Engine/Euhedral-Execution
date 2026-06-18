package euhedral.io.control_plane;

import static euhedral.io.utils.MathFunctions.clampDouble;
import static euhedral.io.utils.MathFunctions.clampLong;
import static euhedral.io.utils.MathFunctions.log2;

import euhedral.hardware_utils.PinnedThreadExecutor;
import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.CpuCacheLayout;
import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.hardware_utils.ThreadTools;
import euhedral.hardware_utils.common.SystemUtilization.CoreSnapshot;
import euhedral.hardware_utils.common.SystemUtilization.CpuSnapshot;
import euhedral.io.config.CacheConfig;
import euhedral.io.config.CloneConfig;
import euhedral.io.config.FragmentConfig;
import euhedral.io.flow_control.BufferedBridge;
import euhedral.io.flow_control.LatticeHotSource;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeSource;
import euhedral.io.metrics.ExecutionMetrics;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;
import euhedral.io.utils.MathFunctions;
import io.euhedral_execution.data_structures.queues.PartitionedMpscQueue;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The core of Euhedral Core
///
/// `ControlPlaneFragment` is the control loop that sits between ingress and execution. It
/// continuously tunes concurrency, dispatch rate, and idle behavior based on what the system is
/// actually doing.
///
/// **It coordinates:**
///
///   - Concurrency (how many frames are in flight)
///   - Dispatch pacing (how fast work is pulled)
///   - Idle behavior (spin -> yield -> park)
///   - SMT buddy coordination
///   - Backpressure and drain control
///
/// It uses a TCP Vegas-style latency model combined with Little’s Law and hardware pressure signals
/// to estimate how much work a core can sustain while keeping latency stable.
///
/// **Goals:**
///
///   - Keep the core busy without overwhelming it
///   - Keep queues short
///   - Avoid latency blowups
///   - Don't thrash the cache
///
/// #### Designed for pinned execution, reactive pipelines, and lock-free queues
///
/// Under load, it pushes harder. Under pressure, it backs off. When idle, it gradually transitions
/// from spinning -> yielding -> parking.
///
/// Frames are the unit of execution: small, composable stages that behave like ultra-lightweight
/// tasks. A pipeline of frames naturally executes in parallel across stages as work flows through.
///
/// **This is the distributed control surface of the system. Everything else is just plumbing.**
public final class ControlPlaneFragment extends WorkRequester {

    private static final long RATE_NS_TO_SEC = 1_000_000_000L;

    private static final VarHandle AVG_LATENCY;
    private static final VarHandle CONCURRENCY;
    private static final VarHandle DRAIN;
    private static final VarHandle IN_FLIGHT;
    private static final VarHandle RATE;
    private static final VarHandle SNAPSHOT;

    static {
        try {
            AVG_LATENCY = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "avgLatency", long.class);
            CONCURRENCY = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "currentConcurrency", long.class);
            DRAIN = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "drainMode", boolean.class);
            IN_FLIGHT = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "inFlight", long.class);
            RATE = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "currentRate", long.class);
            SNAPSHOT = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "coreSnapshot", CoreSnapshot.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static PinnedThreadExecutor createSmtThread(FragmentConfig config) {
        if (config.cloneConfig() == null) {
            return null;
        }

        int[] cpus = config.cloneConfig().getCpuSet();
        if (cpus.length > 1 && config.enableSMT()) {
            return PinnedThreadExecutor.getOrSetIfAbsent(cpus[1],
                    config.cloneConfig().shardName() + "-ControlPlaneFragment-SMT-"
                            + config.cloneConfig().coreId(), Thread.MAX_PRIORITY, false);
        }
        return null;
    }

    public final int cpuId;

    final LatticeHotSource outputStream;

    @Getter
    private final FragmentConfig fragmentConfig;

    private final Logger logger;
    private final ExecutionMetrics metrics;
    private final boolean isPCore;
    private final FlowRecorder executionLatency;
    private final BufferedBridge completeSink;
    private final int maxUpdateInterval;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Getter
    private final PinnedThreadExecutor pinnedExecutor;
    private final Thread shutdownHook;
    private final CycleState state;

    boolean drainMode = false;
    CoreSnapshot coreSnapshot = null;

    long avgLatency;
    long currentConcurrency;
    long currentRate;
    long effectiveConcurrencyLimit;

    long inFlight = 0;
    private long upstreamCount = 0;
    private Thread cycleThread;

    public ControlPlaneFragment(@NonNull CacheConfig cacheConfig,
            @NonNull FragmentConfig fragmentConfig) {
        super(cacheConfig, fragmentConfig.idleCyclePolicy().maxParkTime().toNanos(), createSmtThread(fragmentConfig));
        this.fragmentConfig = fragmentConfig;
        this.maxUpdateInterval =
                Integer.highestOneBit(Math.max(fragmentConfig.maxUpdateInterval(), 2));

        this.currentRate = fragmentConfig.minConcurrency();
        this.currentConcurrency = this.maxUpdateInterval;
        this.effectiveConcurrencyLimit = fragmentConfig.minConcurrency();

        if (fragmentConfig.cloneConfig() == null) {
            this.cpuId = -1;
            this.logger = LoggerFactory.getLogger(ControlPlaneFragment.class);
            this.executionLatency = null;
            this.state = null;
            this.pinnedExecutor = null;
            this.isPCore = false;
            this.metrics = null;
            this.completeSink = null;
            this.outputStream = null;
            this.shutdownHook = null;
        } else {
            String name = fragmentConfig.cloneConfig().shardName() + "-ControlPlaneFragment-"
                    + fragmentConfig.cloneConfig().coreId();
            this.logger = LoggerFactory.getLogger(name);

            int[] cpus = fragmentConfig.cloneConfig().getCpuSet();
            this.cpuId = cpus[0];

            this.executionLatency = super.executionLatency;
            this.state = new CycleState(fragmentConfig.idleCyclePolicy().maxParkTime().toNanos());

            this.pinnedExecutor =
                    PinnedThreadExecutor.getOrSetIfAbsent(cpus[0], name, Thread.MAX_PRIORITY,
                            false);

            CpuCacheLayout layout = SystemInfo.getCacheLayout(cpus[0]);
            this.isPCore =
                    SystemInfo.getCoreInfo(SystemInfo.getCpuInfo(layout.cpu()).core()).pCore();

            this.completeSink =
                    new BufferedBridge(new PartitionedMpscQueue<>(1, fragmentConfig.maxUpdateInterval(), 4), frame -> {
                        IN_FLIGHT.setOpaque(this, this.inFlight - 1);
                        state.receivingOrderedWork = upstreamCount == 1 && frame.isOrdered();
                        state.completed++;
                        frame.reset();
                        frame.doFinally();
                    }, this::recordCompletion);
            this.outputStream = new LatticeHotSource(frame -> {
                if ((this.state.dispatches++ & this.state.updateMask) == 0) {
                    frame.setStartNs(System.nanoTime());
                } else {
                    frame.setStartNs(0);
                }
            });

            this.metrics = new ExecutionMetrics(fragmentConfig.meterRegistry(), fragmentConfig,
                    () -> (int) IN_FLIGHT.getOpaque(this), () -> (long) AVG_LATENCY.getOpaque(this),
                    () -> (long) CONCURRENCY.getOpaque(this), () -> (long) RATE.getOpaque(this));
            this.shutdownHook = new Thread(this::close);
            Runtime.getRuntime().addShutdownHook(this.shutdownHook);
            this.logger.debug("CPU: {} P-Core: {} SMTMode: {}", this.cpuId,
                    this.isPCore, cpus.length > 1 && fragmentConfig.enableSMT());
        }
    }

    @Override
    protected void accept(AbstractFrame frame) {
        this.outputStream.accept(frame);
    }

    @Override
    public LatticeSource output() {
        return this.outputStream;
    }

    @Override
    public void close() {
        if (this.running.compareAndSet(true, false)) {
            if (this.cycleThread != null) {
                try {
                    LockSupport.unpark(this.cycleThread);
                    this.cycleThread.interrupt();
                    this.cycleThread.join(500);
                } catch (Exception ignored) {
                }
                this.cycleThread = null;
            }
            dumpLocks();
            super.pull(AbstractFrame::kill, Long.MAX_VALUE);
            this.metrics.close();
            this.pinnedExecutor.close();
            try {
                Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
            } catch (Exception ignored) {

            }
            super.close();
        }
        this.logger.debug("Closed");
    }

    public void dumpLocks() {
        if (this.pinnedExecutor != null) {
            this.pinnedExecutor.close();
        }
    }

    @Override
    public boolean isStarted() {
        return this.running.getAcquire();
    }

    @Override
    public void start() {
        if (this.pinnedExecutor == null) {
            throw new IllegalStateException(
                    "Pinned Executor has not been set. To start this class, it needs to be instantiated with a CloneConfig.");
        }
        if (this.running.compareAndSet(false, true)) {
            CloneConfig cloneConfig = this.fragmentConfig.cloneConfig();
            if (cloneConfig != null) {
                if (this.pinnedExecutor.isShutdown()) {
                    this.pinnedExecutor.start(
                            this.fragmentConfig.cloneConfig().shardName() + "-ControlPlaneFragment-"
                                    + this.fragmentConfig.cloneConfig().coreId(),
                            Thread.MAX_PRIORITY, false);
                }

                this.pinnedExecutor.execute(() -> {
                    this.cycleThread = Thread.currentThread();

                    CpuInfo origin = ThreadTools.getCpuInfo();
                    if (cloneConfig.coreId() != origin.core()) {
                        this.logger.warn(
                                "Attempted to pin to CPU: {} Core: {} but was assigned: {}",
                                this.cpuId, cloneConfig.coreId(), origin);
                    } else {
                        this.logger.debug("Pinned to Core {} CPU {}", cloneConfig.coreId(),
                                this.cpuId);
                    }
                    ThreadTools.setTimerResolution(1);
                    if (this.fragmentConfig.enableSMT() && cloneConfig.getCpuSet().length > 1) {
                        super.start();
                        this.state.smtMode = true;
                    } else {
                        super.register();
                    }

                    cycle();
                });
            } else {
                cycle();
            }
        }
    }

    private void cycle() {
        try {
            long dispatchWaitNs = 0;
            while (this.running.get() && !Thread.currentThread().isInterrupted()) {
                this.state.receivingOrderedWork = false;
                this.completeSink.drain();
                if (this.state.completed > this.state.updateMask) {
                    updateLimits();
                    this.state.completed = 0;
                }

                this.state.lastActiveNs = System.nanoTime();
                long remaining = dispatchWaitNs - this.state.lastActiveNs;
                if (remaining > 0) {
                    if (remaining < 1_000) {
                        while (System.nanoTime() < dispatchWaitNs) {
                            Thread.onSpinWait();
                        }
                    } else if (remaining < 50_000) {
                        long spinUntil = dispatchWaitNs - 1_000;
                        while (System.nanoTime() < spinUntil) {
                            Thread.onSpinWait();
                        }
                        LockSupport.parkNanos(1_000);
                    } else {
                        LockSupport.parkNanos(remaining);
                    }

                    continue;
                }

                long processed = dispatch();
                if (processed > 0) {
                    dispatchWaitNs = calculateDispatchWaitNs(System.nanoTime());
                    this.state.idleRecorder.record(System.nanoTime(), 0, false);
                    this.state.rests >>>= 1;

                    if ((processed & 127) == 0) {
                        Thread.onSpinWait();
                    }
                    continue;
                }

                long newUpCount = super.getUpstreamCount();
                if (this.upstreamCount != newUpCount) {
                    this.state.idleRecorder.reset(false);
                    this.state.rests = 0;
                    this.upstreamCount = newUpCount;
                }

                this.state.lastEmptyNs = System.nanoTime();

                long cacheCount = super.getCacheCount();
                // This is usually hit when there are producers present but nothing is flowing
                if ((this.state.rests & 15) != 0 && this.upstreamCount > 0
                        && !this.state.receivingOrderedWork && cacheCount == 0
                        && this.state.lastEmptyNs - this.state.lastActiveNs
                        > 10 * this.state.maxParkNs) {
                    idleSpin(Math.min(15, this.state.rests));
                    continue;
                }

                if (cacheCount == 0) {
                    this.state.rests++;
                    idleSpin(Math.min(5, this.state.rests));
                }
            }
        } catch (Throwable e) {
            this.logger.error("[CRITICAL]", e);
        } finally {
            this.running.set(false);
        }
    }

    private long dispatch() {
        long cacheCount = super.getCacheCount();
        if(cacheCount == 0) {
            cacheCount = manuallyPull();
        }
        if(cacheCount == 0) {
            return 0;
        }

        long limit = this.currentConcurrency - this.inFlight;
        limit = Math.min(cacheCount, limit);
        limit = Math.min(this.state.batchSize, limit);

        if (limit > 0) {
            IN_FLIGHT.setOpaque(this, this.inFlight + limit);
            super.drain(limit);
        }
        return limit;
    }

    private void updateLimits() {
        FlowSnapshot flowSnapshot = this.executionLatency.getFlowSnapshot();
        this.executionLatency.refreshSnapshot(flowSnapshot, false);

        double avgVariance = flowSnapshot.unitVariation;
        long updateInterval = this.state.updateMask + 1;
        double scaledVariance = avgVariance * updateInterval;

        // Decrease the sampling rate if the variance is larger than the window.
        if (scaledVariance >= updateInterval) {
            this.state.updateMask(Math.min(updateInterval << 1, this.maxUpdateInterval) - 1);
        } else if (scaledVariance <= (updateInterval >>> 1)) {
            this.state.updateMask(Math.max(2, updateInterval >>> 1) - 1);
        }

        AVG_LATENCY.setOpaque(this, (long) (flowSnapshot.avgUnits + avgVariance));

        double queueEstimate = FlowRecorder.getVegasQueueEstimate(flowSnapshot, this.currentConcurrency);

        RATE.setOpaque(this, (long) flowSnapshot.throughputNs * RATE_NS_TO_SEC);

        // Execution latency only records time units
        // throughputNs = avgRateNs
        // avgRate = units / interval = latencyNs / intervalNs
        long ideal = (long) flowSnapshot.throughputNs * updateInterval;
        ideal = Math.max(ideal, 1L);
        updateEffectiveConcurrencyLimit(ideal);
        updateConcurrency(ideal, queueEstimate);
    }

    /// Updates the maximum allowed in-flight work for this executor.
    ///
    /// The limit scales with observed throughput and current concurrency, then backs off under CPU
    /// pressure to avoid oversaturating the core.
    ///
    /// P-cores are allowed to push harder than E-cores before throttling begins.
    ///
    /// Final limits are clamped against configured minimums and a hardware-derived ceiling.
    private void updateEffectiveConcurrencyLimit(long ideal) {
        CoreSnapshot coreSnapshot = (CoreSnapshot) SNAPSHOT.getOpaque(this);
        CpuSnapshot cpuSnapshot = coreSnapshot.cpuSnapshots()[this.cpuId];

        ideal = Math.max(ideal, this.currentConcurrency);

        long adaptiveCap = ideal << 2;

        double pressure = cpuSnapshot.pressure();
        pressure *= this.isPCore ? 0.5 : 0.7;
        adaptiveCap = (long) (adaptiveCap * (1.0 - pressure));

        long hardwareMax = super.frameQuota;

        this.effectiveConcurrencyLimit =
                clampLong(adaptiveCap, this.fragmentConfig.minConcurrency(), hardwareMax);
    }

    /// Adjusts execution concurrency using a blend of TCP Vegas-style queue estimation and Little’s
    /// Law demand modeling.
    ///
    /// Vegas behavior is used to detect queue pressure:
    ///
    ///   - Low queue residency -> increase concurrency
    ///   - High queue residency -> reduce concurrency
    ///   - Stable queues -> hold steady
    ///
    /// Little’s Law provides a secondary correction based on observed throughput and latency.
    ///
    /// Variability in execution rate and latency expands the ideal target window to avoid
    /// overreacting to bursty workloads.
    ///
    /// Concurrency changes are intentionally conservative:
    ///
    ///   - Small adjustments are ignored
    ///   - Direction changes reset stability tracking
    ///   - Multiple consistent signals are required before tuning
    ///
    /// This prevents oscillation while still allowing the system to react quickly under load.
    private void updateConcurrency(long ideal, double queueEstimate) {
        boolean drain = (boolean) DRAIN.getOpaque(this);
        if (drain) {
            CONCURRENCY.setOpaque(this, this.effectiveConcurrencyLimit);
            return;
        }

        long current = this.currentConcurrency;

        // Vegas thresholds
        long logOfCurrent = Math.max(3, log2(current));
        long alpha = Math.max(3, 3 * logOfCurrent);
        long beta = Math.max(6, 6 * logOfCurrent);

        double vegasFactor;
        if (queueEstimate <= alpha) {
            vegasFactor = (alpha - queueEstimate) / (double) alpha;
        } else if (queueEstimate >= beta) {
            vegasFactor = -(queueEstimate - beta) / (double) beta;
        } else {
            vegasFactor = 0.0;
        }

        vegasFactor = clampDouble(vegasFactor, -1.0, 1.0);

        FlowSnapshot flowSnapshot = this.executionLatency.getFlowSnapshot();
        double cvRate = flowSnapshot.rateCV;
        double cvLatency = flowSnapshot.unitCV;
        double variability = Math.min(1.0, (cvRate + cvLatency) * 0.5);

        ideal = (long) (ideal * (1.0 + variability));

        double littlesFactor = 0.0;
        if (ideal > 0) {
            littlesFactor = (ideal - current) / (double) ideal;
            littlesFactor = clampDouble(littlesFactor, -1.0, 1.0);
        }

        double combined = (vegasFactor * 0.8) + (littlesFactor * 0.2);
        double gain = 0.10;  // max 10% step

        if (Math.signum(combined) != Math.signum(this.state.concurrencyFactor)) {
            this.state.stabilityCounter = 0;
        } else {
            this.state.stabilityCounter++;
        }
        this.state.concurrencyFactor = combined;

        if (Math.abs(combined) < gain || this.state.stabilityCounter < 3) {
            return;
        }

        long next = (long) (current * (1.0 + combined * gain));

        CONCURRENCY.setOpaque(this, clampLong(next, 1, this.effectiveConcurrencyLimit));
    }

    /// Calculates how long dispatch should back off before pulling more work.
    ///
    /// The wait interval expands and contracts dynamically based on:
    ///
    ///   - Observed execution latency
    ///   - Queue pressure (Vegas estimate)
    ///   - Workload variability
    ///   - CPU pressure / throttling
    ///
    /// Under stable low-pressure conditions, dispatch remains aggressive.
    ///
    /// As queue residency, execution variance, or CPU pressure rise, the scheduler increases the
    /// pause interval to reduce contention and avoid runaway queue growth.
    ///
    /// This acts as a lightweight adaptive pacing mechanism for demand signaling.
    private long calculateDispatchWaitNs(long nowNs) {
        if (this.state.maxParkNs <= 0) {
            return 0;
        }

        FlowSnapshot exec = this.executionLatency.getFlowSnapshot();

        double avgLatency = Math.max(exec.avgUnits, 1.0);
        double avgVariance = exec.unitVariation;

        double queuePressure = FlowRecorder.getVegasQueueEstimate(exec, this.currentConcurrency);

        CoreSnapshot core = (CoreSnapshot) SNAPSHOT.getOpaque(this);
        CpuSnapshot cpu = core.cpuSnapshots()[this.cpuId];
        double cpuPressure = cpu.pressure();
        double cpuThrottle = cpuPressure * (this.isPCore ? 0.5 : 0.7);

        long baseIntervalNs = (long) avgLatency;

        double queueFactor =
                1.0 + (queuePressure / (double) (Math.max(this.currentConcurrency, 1)));
        double variability = 1.0 + Math.min(1.0, avgVariance * 0.5);

        long interval = (long) (baseIntervalNs * queueFactor * variability);

        // CPU backpressure
        interval = (long) (interval * (1.0 + cpuThrottle));
        interval = clampLong(interval, 0, this.state.maxParkNs);

        return nowNs + interval;
    }

    /// Adaptive idle strategy for pinned executors.
    ///
    /// The executor progressively de-escalates idle behavior based on observed inactivity:
    ///
    /// ```text
    /// spin -> yield -> park
    /// ```
    ///
    /// Short idle periods stay in active spin to minimize wake latency. As idle time increases, the
    /// executor yields or parks to reduce CPU waste and SMT contention.
    ///
    /// While parked, sibling SMT workers may temporarily steal coordination work to help keep
    /// shared queues moving and reduce cold-start latency when traffic resumes.
    private void idleSpin(long parks) {
        this.state.idleRecorder.record(1, false);

        double idleRatio = this.state.idleRecorder.getRollingAverage(false);
        idleRatio = MathFunctions.clampDouble(idleRatio, 0.0, 1.0);

        if (idleRatio <= this.fragmentConfig.idleCyclePolicy().spinThreshold()) {
            Thread.onSpinWait();
        } else if (idleRatio <= this.fragmentConfig.idleCyclePolicy().yieldThreshold()) {
            Thread.yield();
        } else if (idleRatio <= this.fragmentConfig.idleCyclePolicy().parkThreshold()
                || this.upstreamCount == 0) {
            while (parks-- > 0) {
                park(this.state.maxParkNs);

                if (super.requesterState.bufferCount.get() > 0) {
                    break;
                }

                if (!this.state.smtMode) {
                    if (this.upstreamCount != super.getUpstreamCount()) {
                        break;
                    }

                    if (this.upstreamCount == 0) {
                        long count = super.manuallyPull();
                        if (count > 0) {
                            break;
                        }
                        continue;
                    }

                    if (super.getCacheCount() >= this.state.batchSize >>> 2) {
                        break;
                    }
                }
            }
        }
    }

    private void park(long parkNs) {
        LockSupport.parkNanos(parkNs);
    }

    private void recordCompletion(AbstractFrame frame) {
        if (!frame.isCancelledExecution() && frame.getStartNs() > 0) {
            long now = System.nanoTime();
            this.executionLatency.record(now, now - frame.getStartNs(), this.state.smtMode);
        }
    }

    @Override
    protected long getBatchSize() {
        return this.state.getBatchSize();
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        SNAPSHOT.setOpaque(this, snapshot);
        super.update(snapshot);
    }

    @Override
    public ControlPlaneFragment clone(CloneConfig cloneConfig) {
        return new ControlPlaneFragment(super.cacheConfig.clone(cloneConfig),
                this.fragmentConfig.clone(cloneConfig));
    }

    @Override
    public BufferedBridge completeChannel() {
        return this.completeSink;
    }

    @Override
    public boolean isDrained() {
        return super.isDrained() && (int) IN_FLIGHT.getAcquire(this) == 0;
    }

    @Override
    public void setDrainMode(boolean value) {
        DRAIN.setRelease(this, value);
        super.setDrainMode(value);
    }

    private static class CycleState {

        static final VarHandle BATCH_SIZE;

        static {
            try {
                BATCH_SIZE = MethodHandles.lookup().findVarHandle(CycleState.class, "batchSize", long.class);
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        final long maxParkNs;

        final FlowRecorder idleRecorder = new FlowRecorder();

        boolean smtMode = false;

        long rests = 0;
        long dispatches = 0;
        int completed = 0;

        long lastActiveNs = 0;
        long lastEmptyNs = 0;

        long batchSize = 2;
        long updateMask = 1;
        double concurrencyFactor = 1.0;
        int stabilityCounter = 0;

        boolean receivingOrderedWork = false;

        public CycleState(long maxParkNs) {
            this.maxParkNs = maxParkNs;
        }

        long getBatchSize() {
            if(this.smtMode) {
                return (long) BATCH_SIZE.getAcquire(this);
            }
            return this.batchSize;
        }

        void updateMask(long mask) {
            this.updateMask = mask;
            if(this.smtMode) {
                BATCH_SIZE.setRelease(this, mask + 1);
            } else {
                this.batchSize = mask + 1;
            }
        }
    }
}
