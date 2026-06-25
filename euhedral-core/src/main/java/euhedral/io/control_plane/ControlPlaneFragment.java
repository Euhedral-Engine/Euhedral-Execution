package euhedral.io.control_plane;

import static euhedral.io.utils.MathFunctions.clampDouble;
import static euhedral.io.utils.MathFunctions.clampLong;

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

    private static final VarHandle AVG_LATENCY;
    private static final VarHandle DRAIN;
    private static final VarHandle IN_FLIGHT;
    private static final VarHandle SNAPSHOT;

    static {
        try {
            AVG_LATENCY = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "avgLatency", long.class);
            DRAIN = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "drainMode", boolean.class);
            IN_FLIGHT = MethodHandles.lookup()
                    .findVarHandle(ControlPlaneFragment.class, "inFlight", long.class);
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
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Getter
    private final PinnedThreadExecutor pinnedExecutor;
    private final Thread shutdownHook;
    private final CycleState state;

    boolean drainMode = false;
    CoreSnapshot coreSnapshot = null;

    long avgLatency;
    long effectiveBatchLimit;

    long inFlight = 0;
    private long upstreamCount = 0;
    private Thread cycleThread;

    public ControlPlaneFragment(@NonNull CacheConfig cacheConfig,
            @NonNull FragmentConfig fragmentConfig) {
        super(cacheConfig, fragmentConfig.idleCyclePolicy().maxParkTime().toNanos(), createSmtThread(fragmentConfig));
        this.fragmentConfig = fragmentConfig;

        this.effectiveBatchLimit = 1024;

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
                        frame.reset();
                        frame.doFinally();
                    }, this::recordCompletion);
            this.outputStream = new LatticeHotSource();

            this.metrics = new ExecutionMetrics(fragmentConfig.meterRegistry(), fragmentConfig,
                    () -> (int) IN_FLIGHT.getOpaque(this), () -> (long) AVG_LATENCY.getOpaque(this));
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
                        super.register(getCore());
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
                this.completeSink.drain();

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
        manuallyPull();
        long cacheCount = super.getCacheCount();
        if(cacheCount == 0) {
            return 0;
        }

        long limit = this.state.batchSize - this.inFlight;
        limit = Math.min(cacheCount, limit);

        if (limit > 0) {
            IN_FLIGHT.setOpaque(this, this.inFlight + limit);
            super.drain(limit);
        }
        return limit;
    }

    private void recordCompletion(AbstractFrame frame) {
        this.state.completed++;

        if (this.state.completed >= this.state.batchSize) {
            this.state.completed = 0;

            long now = System.nanoTime();
            this.executionLatency.record(now, 1, this.state.smtMode);

            updateLimits(now);
        }
    }

    private void updateLimits(long nowNs) {
        FlowSnapshot exec = this.executionLatency.getFlowSnapshot();
        FlowSnapshot execThroughput = super.executionThroughput.getFlowSnapshot();

        // Execution latency only records time units
        // avgUnitsOverTime = units / interval = latencyNs / intervalNs = = (1 / intervalNs) * latencyNs
        //
        // Little's Law:
        // pressure = (batchSize / intervalNs) * latencyNs
        long throughput = Math.round(exec.avgUnitsOverTime * this.state.batchSize);
        super.executionThroughput.record(nowNs, throughput, this.state.smtMode);
        super.executionThroughput.refreshSnapshot(execThroughput, this.state.smtMode);
        this.executionLatency.refreshSnapshot(exec, this.state.smtMode);


        double avgLatency = exec.avgInterval / this.state.batchSize;
        AVG_LATENCY.setOpaque(this, (long) avgLatency);

        if(avgLatency == 0) {
            return;
        }

        double latencyCV = execThroughput.intervalCV;
        long batch = this.state.batchSize;

        if(latencyCV > 0.05 && execThroughput.unitCV > 0.0002) { // Fast Rise
            batch += (long) Math.max(Math.sqrt(batch >> 2), 4);
        } else if(throughput > execThroughput.avgUnits) {
            batch = throughput;
        } else if (batch > execThroughput.avgUnits){
            double diff = batch - execThroughput.avgUnits;
            batch -= (long) Math.ceil(Math.max(diff * 0.15, 1));
            batch = Math.max(2L, batch);
        }

        updateEffectiveBatchLimit(batch);

        this.state.updateBatchSize(Math.min(batch, this.effectiveBatchLimit));
    }

    /// Updates the maximum allowed in-flight work for this executor.
    ///
    /// The limit scales with observed throughput and current concurrency, then backs off under CPU
    /// pressure to avoid oversaturating the core.
    ///
    /// P-cores are allowed to push harder than E-cores before throttling begins.
    ///
    /// Final limits are clamped against configured minimums and a hardware-derived ceiling.
    private void updateEffectiveBatchLimit(long ideal) {
        CoreSnapshot coreSnapshot = (CoreSnapshot) SNAPSHOT.getOpaque(this);
        CpuSnapshot cpuSnapshot = coreSnapshot.cpuSnapshots()[this.cpuId];

        long adaptiveCap = ideal << 2;

        double pressure = cpuSnapshot.pressure();
        pressure *= this.isPCore ? 0.5 : 0.7;
        adaptiveCap = (long) (adaptiveCap * (1.0 - pressure));

        long hardwareMax = super.frameQuota;

        this.effectiveBatchLimit =
                clampLong(adaptiveCap, 2, hardwareMax);
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

        CoreSnapshot core = (CoreSnapshot) SNAPSHOT.getOpaque(this);
        CpuSnapshot cpu = core.cpuSnapshots()[this.cpuId];

        double cpuPressure = cpu.pressure() * (this.isPCore ? 0.5 : 0.7);
        double trendFactor = 1.0 + clampDouble(exec.unitTrend, -1.0, 1.0);

        long interval = (long) (avgLatency * Math.max(cpuPressure, trendFactor));

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
                BATCH_SIZE = MethodHandles.lookup().findVarHandle(CycleState.class,
                        "batchSize", long.class);
            } catch (Throwable t) {
                throw new ExceptionInInitializerError(t);
            }
        }

        final long maxParkNs;

        final FlowRecorder idleRecorder = new FlowRecorder();

        boolean smtMode = false;

        long rests = 0;
        int completed = 0;

        long lastActiveNs = 0;
        long lastEmptyNs = 0;

        long batchSize = 3;

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

        void updateBatchSize(long size) {
            if(this.smtMode) {
                BATCH_SIZE.setRelease(this, size);
            } else {
                this.batchSize = size;
            }
        }
    }
}
