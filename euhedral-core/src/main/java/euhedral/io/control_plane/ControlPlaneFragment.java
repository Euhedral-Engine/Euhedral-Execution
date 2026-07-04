package euhedral.io.control_plane;

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
import euhedral.io.flow_control.LatticeHotSource;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.generics.LatticeSource;
import euhedral.io.metrics.ExecutionMetrics;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.FlowThread;
import euhedral.io.utils.MathFunctions;
import io.euhedral_execution.data_structures.atomics.PaddedDoubleAdder;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.Objects;
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

    private static final PaddedDoubleAdder GLOBAL_THROUGHPUT = new PaddedDoubleAdder(SystemInfo.getMaxCoreId() + 1);

    private static final VarHandle AVG_LATENCY;
    private static final VarHandle DRAIN;
    private static final VarHandle IN_FLIGHT;
    private static final VarHandle SNAPSHOT;

    static {
        GLOBAL_THROUGHPUT.fill(Double.NEGATIVE_INFINITY);
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
            return PinnedThreadExecutor.getOrSetIfAbsent(FlowThread.getFactory(), cpus[1],
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
        super(cacheConfig, fragmentConfig.idleCyclePolicy().maxParkTime().toNanos(),
                createSmtThread(fragmentConfig));
        this.fragmentConfig = fragmentConfig;

        this.effectiveBatchLimit = 1024;

        if (fragmentConfig.cloneConfig() == null) {
            this.cpuId = -1;
            this.logger = LoggerFactory.getLogger(ControlPlaneFragment.class);
            this.state = null;
            this.pinnedExecutor = null;
            this.isPCore = false;
            this.metrics = null;
            this.outputStream = null;
            this.shutdownHook = null;
        } else {
            String name = fragmentConfig.cloneConfig().shardName() + "-ControlPlaneFragment-"
                    + fragmentConfig.cloneConfig().coreId();
            this.logger = LoggerFactory.getLogger(name);

            int[] cpus = fragmentConfig.cloneConfig().getCpuSet();
            this.cpuId = cpus[0];

            this.state = new CycleState(fragmentConfig.idleCyclePolicy().maxParkTime().toNanos());

            this.pinnedExecutor =
                    PinnedThreadExecutor.getOrSetIfAbsent(cpus[0], name, Thread.MAX_PRIORITY,
                            false);

            CpuCacheLayout layout = SystemInfo.getCacheLayout(cpus[0]);
            this.isPCore =
                    SystemInfo.getCoreInfo(SystemInfo.getCpuInfo(layout.cpu()).core()).pCore();

            this.outputStream = new LatticeHotSource();

            this.metrics = new ExecutionMetrics(fragmentConfig.meterRegistry(), fragmentConfig,
                    () -> (int) IN_FLIGHT.getOpaque(this),
                    () -> (long) AVG_LATENCY.getOpaque(this));
            this.shutdownHook = new Thread(this::close);
            Runtime.getRuntime().addShutdownHook(this.shutdownHook);
            this.logger.debug("CPU: {} P-Core: {} SMTMode: {}", this.cpuId, this.isPCore,
                    cpus.length > 1 && fragmentConfig.enableSMT());
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
            FlowThread.FlowContext threadContext = FlowThread.getContext();
            Objects.requireNonNull(threadContext);

            double throughput = 0.0;
            while (this.running.getOpaque() && !Thread.currentThread().isInterrupted()) {
                long newUpCount = super.getUpstreamHandleCount();
                if (this.upstreamCount != newUpCount && newUpCount > 0) {
                    this.upstreamCount = newUpCount;
                    GLOBAL_THROUGHPUT.setRelease(super.getCore(), Double.NEGATIVE_INFINITY);
                } else if (this.upstreamCount != newUpCount || this.upstreamCount == 0) {
                    this.upstreamCount = 0;
                    this.state.reset();
                    GLOBAL_THROUGHPUT.setRelease(super.getCore(), Double.NEGATIVE_INFINITY);
                    throughput = 0;
                    idleSpin();
                }

                long now = System.nanoTime();
                long processed = 0;
                FlowRecorder batchEfficiency = this.state.batchEfficiency;
                batchEfficiency.reset();
                while(!Thread.currentThread().isInterrupted()) {
                    processed += execute();
                    if(batchEfficiency.averageUnits() >= 600 || super.getCacheCount() != 0) {
                        Thread.onSpinWait();
                        continue;
                    }

                    double measurements = batchEfficiency.getEffectiveMeasurementWindowCount(batchEfficiency.getLastRecordingTime());
                    if(measurements > 1) {
                        break;
                    }
                    Thread.onSpinWait();
                }

                long requested = 0;
                FlowRecorder requestEfficiency = this.state.requestEfficiency;
                requestEfficiency.reset();
                while(!Thread.currentThread().isInterrupted()) {
                    super.request();
                    long satisfied = threadContext.satisfiedRequest;
                    requested += satisfied;

                    double efficiency = satisfied / (double) Math.max(threadContext.originalRequest, 1);
                    efficiency = MathFunctions.clampDouble(efficiency, 0.0, 1.0);
                    requestEfficiency.record(Math.round(efficiency * 1_000));

                    double measurements = requestEfficiency.getEffectiveMeasurementWindowCount(requestEfficiency.getLastRecordingTime());
                    if(requestEfficiency.averageUnits() < 600 && measurements >= 10) {
                        break;
                    }
                    LockSupport.parkNanos(50_000);
                }
                long totalWork = processed + requested;
                long totalWorkTime = System.nanoTime() - now;
                double instantT = totalWork / (double) totalWorkTime;

                throughput = throughput == 0 ? instantT : MathFunctions.ewma(throughput, instantT, 0.10);
                breakoutSpin(throughput);
            }
        } catch (Throwable e) {
            this.logger.error("[CRITICAL]", e);
        } finally {
            this.running.set(false);
        }
    }

    private long execute() {
        long total = 0;
        while(!Thread.currentThread().isInterrupted()) {
            requestAndPull(this.state.batchSize);

            long cacheCount = super.getCacheCount();
            if (cacheCount == 0) {
                this.state.batchEfficiency.record(0);
                break;
            }

            long limit = this.state.batchSize - this.state.completed;
            limit = Math.min(limit, cacheCount);
            IN_FLIGHT.setOpaque(this, this.inFlight + limit);

            double efficiency = (double) limit / this.state.batchSize;

            long start = System.nanoTime();
            this.state.completed += super.drain(limit);
            long end = System.nanoTime();

            this.state.throughputRecorder.record(end, (end - start) / limit);
            this.state.batchEfficiency.record(end, Math.round((efficiency) * 1_000));

            if(this.state.completed >= this.state.batchSize) {
                this.state.completed = 0;
                this.state.batchRecorder.record(end, this.state.batchSize);
                updateLimits(end);
            }
            Thread.onSpinWait();
        }

        return total;
    }

    private void updateLimits(long nowNs) {
        if (this.state.batchStart == 0) {
            this.state.batchStart = nowNs;
            return;
        }

        AVG_LATENCY.setOpaque(this, Math.round(1.0 / this.state.batchRecorder.averageUnitsOverTime()));

        long currentBatch = this.state.batchSize;

        FlowRecorder recorder = this.state.throughputRecorder;

        double avgThroughput = recorder.averageUnitsOverTime();

        double diffUpper = recorder.getMaxUoT() - avgThroughput;
        double diffLower = avgThroughput - recorder.getMinUoT();

        double averageBatch = Math.round(this.state.batchRecorder.averageUnits());
        if(currentBatch == averageBatch) {
            if (avgThroughput < 0.90) {
                currentBatch += Math.round(Math.max(Math.sqrt(currentBatch), 1));
            } else if(avgThroughput > 1.0){
                currentBatch -= Math.round(Math.max(Math.sqrt(currentBatch), 1));
            } else if(diffLower < diffUpper) {
                currentBatch++;
            } else {
                currentBatch--;
            }
        }

        currentBatch = MathFunctions.clampLong(currentBatch, 2, 4096);

        updateEffectiveBatchLimit(currentBatch);
        currentBatch = Math.min(currentBatch, this.effectiveBatchLimit);

        this.state.batchStart = nowNs;
        this.state.batchSize = currentBatch;
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

        this.effectiveBatchLimit = clampLong(adaptiveCap, 2, hardwareMax);
    }

    private void breakoutSpin(double throughput) {
        int core = super.getCore();
        GLOBAL_THROUGHPUT.setRelease(core, throughput);

        double globalAvgT = GLOBAL_THROUGHPUT.mean();

        if(throughput < globalAvgT) {
            logger.trace("Backoff Spin");
            LockSupport.parkNanos(15_000);
            return;
        }
        Thread.yield();
    }

    private void idleSpin() {
        while(!Thread.currentThread().isInterrupted()) {
            long upCount = super.getUpstreamHandleCount();
            if(upCount != this.upstreamCount) {
                this.upstreamCount = upCount;
                break;
            }
            LockSupport.parkNanos(20_000L);
        }
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
    public boolean isDrained() {
        return super.isDrained() && (long) IN_FLIGHT.getAcquire(this) == 0;
    }

    @Override
    public void setDrainMode(boolean value) {
        DRAIN.setRelease(this, value);
        super.setDrainMode(value);
    }

    private static class CycleState {

        final long maxParkNs;

        final FlowRecorder batchRecorder = new FlowRecorder();
        final FlowRecorder batchEfficiency = new FlowRecorder(Duration.ofSeconds(1), 0.10);
        final FlowRecorder throughputRecorder = new FlowRecorder();
        final FlowRecorder requestEfficiency = new FlowRecorder(Duration.ofSeconds(1), 0.10);
        final FlowRecorder idleRecorder = new FlowRecorder(Duration.ofMillis(5), 0.10);

        long batchStart = 0;
        long batchSize = 2;

        long completed = 0;
        boolean smtMode = false;

        public CycleState(long maxParkNs) {
            this.maxParkNs = maxParkNs;
        }

        void reset() {
            this.batchRecorder.reset();
            this.batchEfficiency.reset();
            this.requestEfficiency.reset();
            this.idleRecorder.reset();
            this.throughputRecorder.reset();

            this.batchStart = 0;
            this.batchSize = 2;
            this.completed = 0;
        }
    }
}
