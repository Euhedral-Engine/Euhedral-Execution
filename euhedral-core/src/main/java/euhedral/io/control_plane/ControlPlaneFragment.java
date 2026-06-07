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
import euhedral.io.config.CloneConfig;
import euhedral.io.config.SchedulingConfig;
import euhedral.io.control_plane.ControlPlaneCache.DownstreamHandle;
import euhedral.io.control_plane.SMTBuddy.SMTState;
import euhedral.io.flow_control.BufferedBridge;
import euhedral.io.flow_control.DirectOutputStream;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.DummyInitFrame;
import euhedral.io.generics.LatticeSource;
import euhedral.io.generics.SlotManager;
import euhedral.io.metrics.ExecutionMetrics;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;
import euhedral.queues.PartitionedMpscQueue;
import euhedral.queues.PartitionedSpscQueue;
import euhedral.queues.common.PartitionedQueue;
import euhedral.queues.common.QueueUtils;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import lombok.AccessLevel;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// ## The core of Euhedral Core
///
/// `ControlPlaneFragment` is the control loop that sits between ingress and execution. It continuously
/// tunes concurrency, dispatch rate, and idle behavior based on what the system is actually doing.
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
@Getter(AccessLevel.PROTECTED)
public class ControlPlaneFragment implements SlotManager {

    protected static final long RATE_NS_TO_SEC = 1_000_000_000L;

    protected static final VarHandle AVG_LATENCY;
    protected static final VarHandle CONCURRENCY;
    protected static final VarHandle DRAIN;
    protected static final VarHandle INGEST;
    protected static final VarHandle IN_FLIGHT;
    protected static final VarHandle RATE;
    protected static final VarHandle SNAPSHOT;

    static {
        try {
            AVG_LATENCY = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "avgLatency", long.class);
            CONCURRENCY = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "currentConcurrency", long.class);
            DRAIN = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "drainMode", boolean.class);
            INGEST = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "ingest", ControlPlaneCache.class);
            IN_FLIGHT = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "inFlight", int.class);
            RATE = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "currentRate", long.class);
            SNAPSHOT = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "coreSnapshot", CoreSnapshot.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }


    public final int cpuId;

    @Getter
    protected final SchedulingConfig config;
    protected final ExecutionMetrics metrics;
    protected final Logger logger;
    protected final boolean isPCore;
    protected final AtomicBoolean running = new AtomicBoolean(false);

    protected final FlowRecorder executionLatency;

    protected final int bufferSize;
    protected final DrainBuffer bufferWrapper;
    protected final PartitionedQueue<AbstractFrame> buffer;
    protected final BufferedBridge completeSink;

    protected final int maxUpdateInterval;

    @Getter
    protected final PinnedThreadExecutor pinnedExecutor;
    protected final Thread shutdownHook;
    protected final DownstreamHandle handle;

    protected final DirectOutputStream outputStream;

    protected final CycleState state;
    protected final SMTState buddyState;
    protected final SMTBuddy buddy;

    protected boolean drainMode = false;
    protected CoreSnapshot coreSnapshot = null;

    protected ControlPlaneCache ingest = null;

    protected long avgLatency;
    protected long currentConcurrency;
    protected long currentRate;
    protected long effectiveConcurrencyLimit;

    protected int inFlight = 0;

    protected long upstreamCount = 0;

    protected boolean primed = false;
    private Thread cycleThread;

    public ControlPlaneFragment(@NonNull SchedulingConfig config) {
        this.config = config;
        this.maxUpdateInterval = Integer.highestOneBit(Math.max(config.maxUpdateInterval(), 2));

        this.currentRate = config.minConcurrency();
        this.currentConcurrency = Math.max(1, config.minConcurrency());
        this.effectiveConcurrencyLimit = config.minConcurrency();

        if (config.cloneConfig() == null) {
            this.cpuId = -1;
            this.logger = LoggerFactory.getLogger(ControlPlaneFragment.class);
            this.executionLatency = null;
            this.state = null;
            this.pinnedExecutor = null;
            this.buffer = null;
            this.bufferSize = 0;
            this.bufferWrapper = null;
            this.buddy = null;
            this.buddyState = null;
            this.isPCore = false;
            this.metrics = null;
            this.completeSink = null;
            this.outputStream = null;
            this.shutdownHook = null;
            this.handle = null;
        } else {
            String name =
                    config.cloneConfig().shardName() + "-ControlPlaneFragment-" + config.cloneConfig()
                            .coreId();
            this.logger = LoggerFactory.getLogger(name);

            int[] cpus = config.cloneConfig().getCpuSet();
            this.cpuId = cpus[0];

            this.executionLatency = new FlowRecorder();
            this.state = new CycleState();

            CpuCacheLayout layout = SystemInfo.getCacheLayout(cpus[0]);
            long temp = layout.bytesL1();
            temp = (long) (temp * 0.7);
            int bufferSize = (int) Math.min(Long.highestOneBit((temp - 1) << 1), Integer.MAX_VALUE);
            bufferSize /= QueueUtils.REFERENCE_SIZE;
            bufferSize = Math.max(bufferSize, 64);
            this.bufferSize = bufferSize;

            this.pinnedExecutor =
                    PinnedThreadExecutor.getOrSetIfAbsent(cpus[0], name, Thread.MAX_PRIORITY,
                            false);

            PinnedThreadExecutor smtExec = null;
            if (cpus.length > 1 && config.enableSMT()) {
                smtExec = PinnedThreadExecutor.getOrSetIfAbsent(cpus[1],
                        this.config.cloneConfig().shardName() + "-ControlPlaneFragment-SMT-"
                                + this.config.cloneConfig().coreId(), Thread.MAX_PRIORITY, false);
            }
            this.buffer = new PartitionedSpscQueue<>(bufferSize);
            this.handle = new DownstreamHandle(this.cpuId, this::getPressure);
            this.bufferWrapper = new DrainBuffer(this.buffer, bufferSize, false);
            this.buddyState = new SMTState(executionLatency, bufferWrapper.arrivalLatencyRecorder,
                    config.idleCyclePolicy().maxParkTime().toNanos());
            this.buddy = new SMTBuddy(handle, bufferWrapper, buddyState, smtExec);
            this.isPCore =
                    SystemInfo.getCoreInfo(SystemInfo.getCpuInfo(layout.cpu()).core()).pCore();

            this.completeSink =
                    new BufferedBridge(new PartitionedMpscQueue<>(1, bufferSize, 4),
                            frame -> {
                                IN_FLIGHT.setOpaque(this, this.inFlight - 1);
                                state.receivingOrderedWork =
                                        upstreamCount == 1 && frame.isOrdered();
                                state.completed++;
                                frame.reset();
                                frame.doFinally();
                            }, this::recordCompletion);
            this.outputStream = new DirectOutputStream(this.buffer, frame -> {
                if ((this.state.dispatches++ & this.state.updateIntervalMask) == 0) {
                    frame.setStartNs(System.nanoTime());
                } else {
                    frame.setStartNs(0);
                }
            });

            this.metrics = new ExecutionMetrics(config.meterRegistry(), config,
                    () -> (int) IN_FLIGHT.getOpaque(this), () -> (long) AVG_LATENCY.getOpaque(this),
                    () -> (long) CONCURRENCY.getOpaque(this), () -> (long) RATE.getOpaque(this),
                    this::getPressure);
            this.shutdownHook = new Thread(this::close);
            Runtime.getRuntime().addShutdownHook(this.shutdownHook);
            this.logger.debug("CPU: {} P-Core: {} SMTMode: {} BufferCapacity: {}", this.cpuId,
                    this.isPCore, cpus.length > 1 && config.enableSMT(), bufferSize);
        }
    }

    @Override
    public void input(LatticeSource stream) {
        if (stream instanceof ControlPlaneCache iStream && INGEST.compareAndSet(this, null,
                iStream)) {
            this.buddy.setIngest(iStream);
            INGEST.setRelease(this, iStream);
            iStream.addHandle(this.handle);
        }
    }

    @Override
    public LatticeSource output() {
        return this.outputStream;
    }

    @Override
    public void close() {
        if (this.running.compareAndSet(true, false)) {
            ControlPlaneCache ingest = (ControlPlaneCache) INGEST.getAcquire(this);
            if (ingest != null) {
                ingest.removeThread(this.cycleThread);
                ingest.removeHandle(this.cpuId);
                ingest.close();
            }
            this.buddy.close();
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
            AbstractFrame frame;
            while ((frame = this.buffer.poll(0)) != null) {
                frame.kill();
            }
            this.buffer.clear();
            this.metrics.close();
            this.pinnedExecutor.close();
            try {
                Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
            } catch (Exception ignored) {

            }
        }
        this.logger.debug("Closed");
    }

    public void dumpLocks() {
        if (this.pinnedExecutor != null) {
            this.pinnedExecutor.close();
        }
    }

    @Override
    public void firstTouch() {
        if (this.running.getAcquire()) {
            return;
        }
        if (this.buffer == null) {
            return;
        }
        for (int i = 0; i < this.bufferSize * 2; i++) {
            this.buffer.offer(DummyInitFrame.INSTANCE);
        }
        this.buffer.clear();
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
            CloneConfig cloneConfig = this.config.cloneConfig();
            if (cloneConfig != null) {
                if (this.pinnedExecutor.isShutdown()) {
                    this.pinnedExecutor.start(
                            this.config.cloneConfig().shardName() + "-ControlPlaneFragment-"
                                    + this.config.cloneConfig().coreId(), Thread.MAX_PRIORITY,
                            false);
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
                    if (this.config.enableSMT() && cloneConfig.getCpuSet().length > 1) {
                        this.buddy.start();
                        this.state.smtMode = true;
                    }

                    while (this.ingest == null && !Thread.currentThread().isInterrupted()) {
                        this.ingest = (ControlPlaneCache) INGEST.getOpaque(this);
                        this.buddy.setIngest(ingest);
                        LockSupport.parkNanos(2_000L);
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    this.ingest.register();
                    cycle();
                });
            } else {
                cycle();
            }
        }
    }

    protected void cycle() {
        try {
            long dispatchWaitNs = 0;
            while (this.running.get() && !Thread.currentThread().isInterrupted()) {
                this.state.receivingOrderedWork = false;
                this.completeSink.drain();
                if (this.state.completed > this.state.updateIntervalMask) {
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

                int processed = dispatch();
                if (processed > 0) {
                    dispatchWaitNs = calculateDispatchWaitNs(System.nanoTime());
                    this.state.idleRecorder.record(System.nanoTime(), 0, false);
                    this.state.rests >>>= 1;

                    if ((processed & 127) == 0) {
                        Thread.onSpinWait();
                    }
                    continue;
                }

                long newUpCount = this.ingest.getUpstreamCount();
                if (this.upstreamCount != newUpCount) {
                    this.state.idleRecorder.reset(false);
                    this.state.rests = 0;
                    this.upstreamCount = newUpCount;
                }

                this.state.lastEmptyNs = System.nanoTime();
                if (!this.state.smtMode && this.state.lastEmptyNs > buddyState.demandWaitNs) {
                    this.buddy.doStuff();
                }

                long ingestCount = this.ingest.getTotalCount();
                int bufferCount = this.buddyState.bufferCount.getAcquire();
                // This is usually hit when there are producers present but nothing is flowing
                if (processed == 0 && (this.state.rests & 15) != 0 && this.upstreamCount > 0
                        && !this.state.receivingOrderedWork && ingestCount == 0 && bufferCount == 0
                        && this.state.lastEmptyNs - this.state.lastActiveNs
                        > 10 * this.state.maxParkNs) {
                    idleSpin(Math.min(15, this.state.rests));
                    continue;
                }

                if (processed == 0 && bufferCount == 0) {
                    this.state.rests++;
                }
            }
        } catch (Throwable e) {
            this.logger.error("[CRITICAL]", e);
        } finally {
            this.running.set(false);
        }
    }

    protected int dispatch() {
        int bufferCount = this.buddyState.bufferCount.getAcquire();
        if (bufferCount == 0) {
            return 0;
        }

        long currentConcurrency = this.currentConcurrency;
        int quota = (int) Math.max(0, currentConcurrency - this.inFlight);

        boolean drain = (boolean) DRAIN.getOpaque(this);
        quota = drain ? bufferCount : quota;

        int processed = 0;
        if (quota > 0 && bufferCount > 0) {
            IN_FLIGHT.setOpaque(this, this.inFlight + quota);
            processed = (int) this.outputStream.push(quota);
            if (processed > 0) {
                this.buddyState.bufferCount.addAndGet(-processed);
                IN_FLIGHT.setOpaque(this, this.inFlight + (processed - quota));
            }
        }
        return processed;
    }

    protected void updateLimits() {
        FlowSnapshot flowSnapshot = this.executionLatency.getFlowSnapshot();
        this.executionLatency.refreshSnapshot(flowSnapshot, false);

        double avgVariance = flowSnapshot.unitVariation;
        int updateInterval = this.state.updateIntervalMask + 1;
        double scaledVariance = avgVariance * updateInterval;

        // Decrease the sampling rate if the variance is larger than the window.
        if (scaledVariance >= updateInterval) {
            this.state.updateIntervalMask =
                    Math.min(updateInterval << 1, this.maxUpdateInterval) - 1;
        } else if (scaledVariance <= (updateInterval >>> 1)) {
            this.state.updateIntervalMask = Math.max(2, updateInterval >>> 1) - 1;
        }

        AVG_LATENCY.setOpaque(this, (long) (flowSnapshot.avgUnits + avgVariance));

        double queueEstimate =
                this.executionLatency.getVegasQueueEstimate(flowSnapshot, flowSnapshot.avgUnits,
                        this.currentConcurrency);

        RATE.setOpaque(this, flowSnapshot.throughputNs * RATE_NS_TO_SEC);
        long ideal = flowSnapshot.throughputNs * updateInterval;
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
    protected void updateEffectiveConcurrencyLimit(long ideal) {
        CoreSnapshot coreSnapshot = (CoreSnapshot) SNAPSHOT.getOpaque(this);
        CpuSnapshot cpuSnapshot = coreSnapshot.cpuSnapshots()[this.cpuId];

        ideal = Math.max(ideal, this.currentConcurrency);

        long adaptiveCap = ideal << 2;

        double pressure = cpuSnapshot.pressure();
        pressure *= this.isPCore ? 0.5 : 0.7;
        adaptiveCap = (long) (adaptiveCap * (1.0 - pressure));

        long cpuCount = cpuSnapshot.globalCpuCount();
        long hardwareMax = cpuCount * this.bufferSize;

        this.effectiveConcurrencyLimit =
                clampLong(adaptiveCap, this.config.minConcurrency(), hardwareMax);
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
    protected void updateConcurrency(long ideal, double queueEstimate) {
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
    protected long calculateDispatchWaitNs(long nowNs) {
        if (this.state.maxParkNs <= 0) {
            return 0;
        }

        FlowSnapshot exec = this.executionLatency.getFlowSnapshot();

        double avgLatency = Math.max(exec.avgUnits, 1.0);
        double avgVariance = exec.unitVariation;

        double queuePressure = this.executionLatency.getVegasQueueEstimate(exec, exec.avgUnits,
                this.currentConcurrency);

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
    protected void idleSpin(long parks) {
        long now = System.nanoTime();
        this.state.idleRecorder.record(now, 1, false);

        double idleRatio = this.state.idleRecorder.getRollingAverage(now, false);
        if (idleRatio <= this.config.idleCyclePolicy().spinThreshold()) {
            Thread.onSpinWait();
        } else if (idleRatio <= this.config.idleCyclePolicy().yieldThreshold()) {
            Thread.yield();
        } else if (idleRatio <= this.config.idleCyclePolicy().parkThreshold()
                || this.upstreamCount == 0) {
            while (parks-- > 0) {
                park(this.state.maxParkNs);

                if (this.buddyState.bufferCount.get() > 0) {
                    break;
                }

                ControlPlaneCache ingest = (ControlPlaneCache) INGEST.getOpaque(this);
                if (!this.state.smtMode) {
                    if (this.upstreamCount != ingest.getUpstreamCount()) {
                        break;
                    }

                    if (this.upstreamCount == 0) {
                        this.buddy.doStuff();
                        long count = this.buddyState.bufferCount.getOpaque();
                        if (count > 0) {
                            break;
                        }
                        continue;
                    }

                    if (ingest.getTotalCount() >= (this.bufferSize >> 3)) {
                        this.buddy.doStuff();
                        break;
                    }
                }
            }
        }
    }

    protected final void park(long parkNs) {
        LockSupport.parkNanos(parkNs);
    }

    protected void recordCompletion(AbstractFrame frame) {
        if (!frame.isCancelledExecution() && frame.getStartNs() > 0) {
            long now = System.nanoTime();
            this.executionLatency.record(now, now - frame.getStartNs(), false);
        }
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        SNAPSHOT.setOpaque(this, snapshot);
    }

    @Override
    public double getPressure() {
        long concurrency = (long) CONCURRENCY.getAcquire(this);
        long alpha = Math.max(3, 3 * Math.max(3, log2(concurrency)));
        long beta = Math.max(6, 6 * alpha);

        FlowSnapshot snapshot = this.executionLatency.getFlowSnapshot();
        this.executionLatency.refreshSnapshot(snapshot, true);
        double queueEstimate = this.executionLatency.getVegasQueueEstimate(snapshot,
                snapshot.avgUnits + snapshot.unitVariation, concurrency);

        double vegasPressure = queueEstimate / beta;

        CoreSnapshot core = (CoreSnapshot) SNAPSHOT.getOpaque(this);
        double hardwarePressure = 0.0;
        if (core != null) {
            hardwarePressure = core.cpuSnapshots()[this.cpuId].pressure();
        }

        double base = Math.max(vegasPressure, hardwarePressure);
        return clampDouble(base, 0.0, 1.0);
    }

    @Override
    public ControlPlaneFragment clone(CloneConfig cloneConfig) {
        return new ControlPlaneFragment(this.config.clone(cloneConfig));
    }

    @Override
    public BufferedBridge completeChannel() {
        return this.completeSink;
    }

    @Override
    public boolean isDrained() {
        return (int) IN_FLIGHT.getAcquire(this) == 0 && this.buffer.isEmpty();
    }

    @Override
    public void setDrainMode(boolean value) {
        DRAIN.setRelease(this, value);
    }

    protected class CycleState {

        public final long maxParkNs = config.idleCyclePolicy().maxParkTime().toNanos();

        public final FlowRecorder idleRecorder = new FlowRecorder();

        public boolean smtMode = false;

        public long rests = 0;
        public long dispatches = 0;
        public int completed = 0;

        public long lastActiveNs = 0;
        public long lastEmptyNs = 0;

        public int updateIntervalMask = 1;
        public double concurrencyFactor = 1.0;
        public int stabilityCounter = 0;

        public boolean receivingOrderedWork = false;
    }
}
