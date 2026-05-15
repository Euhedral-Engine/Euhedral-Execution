package euhedral.io;

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
import euhedral.io.SlotManagerSMTBuddy.SMTState;
import euhedral.io.config.CloneConfig;
import euhedral.io.config.ExecutionManagerConfig;
import euhedral.io.flow_control.DirectOutputFlux;
import euhedral.io.flow_control.IngestSequencer;
import euhedral.io.flow_control.IngestSequencer.WakeHook;
import euhedral.io.flow_control.LockFreeSink;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.DummyInitFrame;
import euhedral.io.interfaces.SlotManager;
import euhedral.io.metrics.ExecutionManagerMetrics;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;
import euhedral.io.utils.ObjectSizer;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import lombok.AccessLevel;
import lombok.Getter;
import org.jctools.queues.MpscUnboundedXaddArrayQueue;
import org.jctools.queues.unpadded.SpscUnboundedUnpaddedArrayQueue;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.CoreSubscriber;

/**
 * Adaptive concurrency and rate control implementation designed to provide stable, resource-aware
 * ingress governance.
 *
 * <p>This class regulates request dispatch using layered feedback mechanisms:
 *
 * <ul>
 *     <li>Latency-based adaptive concurrency (Vegas-style estimation)</li>
 *     <li>Resource-aware concurrency envelope (CPU and memory pressure)</li>
 *     <li>Dynamic waiter queue capping</li>
 *     <li>Configurable overload handling (reject, delay, or drop)</li>
 *     <li>Integrated rate limiting and circuit breaking</li>
 * </ul>
 *
 * <h2>Control Model</h2>
 *
 * <ul>
 *     <li><b>Effective maximum</b> - resource-adjusted concurrency envelope.</li>
 *     <li><b>Current concurrency</b> - latency-driven adaptive value bounded by effective maximum.</li>
 *     <li><b>Waiters</b> - how many processes are waiting for slots </li>
 * </ul>
 * <p>
 * The effective maximum is derived from CPU and memory utilization and
 * updated smoothly to prevent oscillation. The adaptive concurrency logic
 * adjusts within this envelope based on observed latency and queueing.
 *
 * <h2>Threading</h2>
 * <p>
 * This class is non-blocking and designed for use with reactive pipelines
 * and virtual threads. Coordination relies on atomic primitives and
 * lock-free data structures.
 *
 * <p> Intended for use as a global ingress governor or per-service adaptive
 * dispatcher.</p>
 * </p>
 */
@Getter(AccessLevel.PROTECTED)
public class ExecutionManager implements SlotManager {

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
                    .findVarHandle(ExecutionManager.class, "avgLatency", long.class);
            CONCURRENCY = MethodHandles.lookup()
                    .findVarHandle(ExecutionManager.class, "currentConcurrency", long.class);
            DRAIN = MethodHandles.lookup()
                    .findVarHandle(ExecutionManager.class, "drainMode", boolean.class);
            INGEST = MethodHandles.lookup()
                    .findVarHandle(ExecutionManager.class, "ingest", IngestSequencer.class);
            IN_FLIGHT = MethodHandles.lookup()
                    .findVarHandle(ExecutionManager.class, "inFlight", int.class);
            RATE = MethodHandles.lookup()
                    .findVarHandle(ExecutionManager.class, "currentRate", long.class);
            SNAPSHOT = MethodHandles.lookup()
                    .findVarHandle(ExecutionManager.class, "coreSnapshot", CoreSnapshot.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }


    public final int cpuId;

    @Getter
    protected final ExecutionManagerConfig config;
    protected final ExecutionManagerMetrics metrics;
    protected final Logger logger;
    protected final boolean isPCore;
    protected final AtomicBoolean running = new AtomicBoolean(false);

    protected final FlowRecorder executionLatency;

    protected final int bufferSize;
    protected final DrainBuffer bufferWrapper;
    protected final SpscUnboundedUnpaddedArrayQueue<AbstractFrame> buffer;
    protected final LockFreeSink completeSink;

    protected final int maxUpdateInterval;

    @Getter
    protected final PinnedThreadExecutor pinnedExecutor;
    protected final Thread shutdownHook;

    protected final DirectOutputFlux outputFlux;

    protected final CycleState state;
    protected final SMTState buddyState;
    protected final SlotManagerSMTBuddy buddy;

    protected boolean drainMode = false;
    protected CoreSnapshot coreSnapshot = null;

    protected IngestSequencer ingest = null;

    protected long avgLatency;
    protected long currentConcurrency;
    protected long currentRate;
    protected long effectiveConcurrencyLimit;

    protected int inFlight = 0;

    protected long upstreamCount = 0;

    protected WakeHook wakeHook;
    private Thread cycleThread;

    public ExecutionManager(@NonNull ExecutionManagerConfig config) {
        this.config = config;

        int bufferSize = (int) Math.min(Long.highestOneBit((SystemInfo.DEFAULT_L1 - 1) << 1),
                Integer.MAX_VALUE);
        if (config.cloneConfig() != null) {
            CpuCacheLayout layout = SystemInfo.getCacheLayout(config.cloneConfig().getCpuSet()[0]);
            long temp = layout.bytesL1();
            if (config.cloneConfig().getCpuSet().length != layout.sharesL1()) {
                temp /= layout.sharesL1();
            }

            temp = (long) (temp * 0.7);
            bufferSize = (int) Math.min(Long.highestOneBit((temp - 1) << 1), Integer.MAX_VALUE);
            bufferSize /= ObjectSizer.POINTER_SIZE;
            isPCore = SystemInfo.getCoreInfo(SystemInfo.getCpuInfo(layout.cpu()).core()).pCore();
        } else {
            isPCore = false;
        }
        bufferSize = Math.max(bufferSize, 64);

        this.buffer = new SpscUnboundedUnpaddedArrayQueue<>(bufferSize);
        this.bufferSize = bufferSize;
        this.bufferWrapper = new DrainBuffer(buffer, bufferSize, false);

        this.executionLatency = new FlowRecorder();
        this.maxUpdateInterval = Integer.highestOneBit(Math.max(config.maxUpdateInterval(), 2));

        this.state = new CycleState();


        this.currentRate = config.minConcurrency();
        this.currentConcurrency = Math.max(1, config.minConcurrency());
        this.effectiveConcurrencyLimit = config.minConcurrency();

        PinnedThreadExecutor smtExec = null;
        if (config.cloneConfig() == null) {
            this.cpuId = -1;
            this.pinnedExecutor = null;
            this.logger = LoggerFactory.getLogger(ExecutionManager.class);
        } else {
            int[] cpus = config.cloneConfig().getCpuSet();
            this.cpuId = cpus[0];
            String name = config.cloneConfig().shardName() + "-ExecutionManager-"
                    + config.cloneConfig().coreId();

            this.pinnedExecutor =
                    PinnedThreadExecutor.getOrSetIfAbsent(cpus[0], name, Thread.MAX_PRIORITY,
                            false);
            if(cpus.length > 1) {
                smtExec = PinnedThreadExecutor.getOrSetIfAbsent(cpus[1],
                        this.config.cloneConfig().shardName() + "-ExecutionManager-SMT-"
                                + this.config.cloneConfig().coreId(), Thread.MAX_PRIORITY,
                        false);
            }
            this.logger = LoggerFactory.getLogger(name);
        }

        this.buddyState = new SMTState(executionLatency, bufferWrapper.arrivalLatencyRecorder,
                config.idleCyclePolicy().maxParkTime().toNanos());

        this.buddy = new SlotManagerSMTBuddy(bufferWrapper, buddyState, smtExec);

        this.metrics = new ExecutionManagerMetrics(config.meterRegistry(), config,
                () -> (int) IN_FLIGHT.getOpaque(this),
                () -> (long) AVG_LATENCY.getOpaque(this),
                () -> (long) CONCURRENCY.getOpaque(this),
                () -> (long) RATE.getOpaque(this),
                this::getPressure);

        this.completeSink =
                new LockFreeSink(new MpscUnboundedXaddArrayQueue<>(bufferSize, 4), frame -> {
                    IN_FLIGHT.setOpaque(this, this.inFlight - 1);
                    state.receivingOrderedWork = upstreamCount == 1 && frame.isOrdered();
                    state.completed++;
                    frame.doFinally();
                }, this::recordCompletion);
        this.outputFlux = new DirectOutputFlux(this.buffer, frame -> {
            if ((this.state.dispatches++ & this.state.updateIntervalMask) == 0) {
                frame.setStartNs(System.nanoTime());
            } else {
                frame.setStartNs(0);
            }
            frame.setCompletionSink(this.completeSink);
        });

        this.shutdownHook = new Thread(this::close);
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    protected void recordCompletion(AbstractFrame frame) {
        if (!frame.isCancelledExecution() && frame.getStartNs() > 0) {
            long now = System.nanoTime();
            this.executionLatency.record(now, now - frame.getStartNs(), false);
        }
    }

    @Override
    public void close() {
        if (this.running.compareAndSet(true, false)) {
            IngestSequencer ingest = (IngestSequencer) INGEST.getAcquire(this);
            if (ingest != null) {
                ingest.removeThread(this.cycleThread);
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
            while ((frame = this.buffer.poll()) != null) {
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
        this.logger.info("Closed");
    }

    public void dumpLocks() {
        if (this.pinnedExecutor != null) {
            this.pinnedExecutor.close();
        }
    }

    @Override
    public void firstTouch() {
        for (int i = 0; i < this.bufferSize * 2; i++) {
            this.buffer.add(DummyInitFrame.INSTANCE);
        }
        this.buffer.clear();
    }

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
                            this.config.cloneConfig().shardName() + "-ExecutionManager-"
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
                        this.logger.info("Pinned to Core {} CPU {}", cloneConfig.coreId(),
                                this.cpuId);
                    }
                    ThreadTools.setTimerResolution(1);
                    if (this.config.enableSMT() && cloneConfig.getCpuSet().length > 1) {
                        this.buddy.start();
                        this.state.smtMode = true;
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

                IngestSequencer ingest = (IngestSequencer) INGEST.getOpaque(this);
                if (ingest == null) {
                    idleSpin(5);
                    continue;
                }

                long ingestCount = ingest.getCount();

                if (this.buddyState.bufferCount.get() > this.state.lowWaterMark
                        && ingestCount == 0) {
                    idleSpin(this.state.rests);
                    continue;
                }

                long newUpCount = ingest.getUpstreamCount();
                if (this.upstreamCount != newUpCount) {
                    this.state.idleRecorder.reset(false);
                    this.state.rests = 0;
                    this.upstreamCount = newUpCount;
                }
                // This is usually hit when there are producers present but nothing is flowing
                else if (processed == 0 && (this.state.rests & 15) != 0 && this.upstreamCount > 0
                        && !this.state.receivingOrderedWork && ingestCount == 0
                        && this.state.lastEmptyNs - this.state.lastActiveNs
                        > 10 * this.state.maxParkNs) {
                    idleSpin(Math.min(15, this.state.rests));
                    continue;
                }

                this.state.lastEmptyNs = System.nanoTime();
                if (!this.state.smtMode && this.state.lastEmptyNs > buddyState.demandWaitNs) {
                    this.buddy.doStuff();
                }

                this.state.rests++;
            }
        } catch (Throwable e) {
            this.logger.error("[CRITICAL]", e);
        } finally {
            this.running.set(false);
        }
    }

    protected int dispatch() {
        int bufferCount = this.buddyState.bufferCount.get();
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
            processed = this.outputFlux.drain(quota);
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

    protected long calculateDispatchWaitNs(long nowNs) {
        FlowSnapshot exec = this.executionLatency.getFlowSnapshot();

        double avgLatency = Math.max(exec.avgUnits, 1.0);
        double avgVariance = exec.unitVariation;

        double queuePressure =
                this.executionLatency.getVegasQueueEstimate(exec, exec.avgUnits,
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

    protected void idleSpin(long parks) {
        long now = System.nanoTime();
        this.state.idleRecorder.record(now, 1, false);

        double idleRatio = this.state.idleRecorder.getRollingAverage(now, false);
        if (idleRatio <= this.config.idleCyclePolicy().spinThreshold()) {
            Thread.onSpinWait();
        } else if (idleRatio <= this.config.idleCyclePolicy().yieldThreshold()) {
            Thread.yield();
        } else {
            while (parks-- > 0) {
                park(this.state.maxParkNs);

                if (this.buddyState.bufferCount.get() > 0) {
                    break;
                }

                IngestSequencer ingest = (IngestSequencer) INGEST.getOpaque(this);
                if (ingest != null && !this.state.smtMode) {
                    if (this.upstreamCount != ingest.getUpstreamCount()) {
                        break;
                    }

                    int bufferCount = this.buddyState.bufferCount.get();
                    if (this.upstreamCount == 0) {
                        long count = ingest.drain(this.bufferWrapper,
                                this.bufferSize - bufferCount, 0);
                        if (count > 0) {
                            buddyState.bufferCount.addAndGet((int) count);
                            break;
                        }
                    }

                    if (ingest.getCount() >= (this.bufferSize >> 3)) {
                        long count = ingest.drain(this.bufferWrapper,
                                this.bufferSize - bufferCount, 0);
                        this.buddyState.bufferCount.addAndGet((int) count);
                        break;
                    }
                }
            }
        }
    }

    protected final void park(long parkNs) {
        if (this.wakeHook != null) {
            this.wakeHook.parked = true;
        }
        LockSupport.parkNanos(parkNs);
        if (this.wakeHook != null) {
            this.wakeHook.parked = false;
        }
    }

    @Override
    public Publisher<? extends AbstractFrame> process(Publisher<? extends AbstractFrame> flux) {
        ingest(flux);
        return output();
    }

    @Override
    public void ingest(Publisher<? extends AbstractFrame> frameFlux) {
        if (frameFlux instanceof IngestSequencer sequencer && INGEST.compareAndSet(this, null,
                sequencer)) {
            this.buddy.setIngest(sequencer);
            this.wakeHook = new WakeHook(this.cycleThread);
            sequencer.setWakeHook(this.wakeHook);
            LockSupport.unpark(this.cycleThread);
        }
    }

    @Override
    public Publisher<? extends AbstractFrame> output() {
        return this.outputFlux;
    }

    @Override
    public boolean isStarted() {
        return this.running.getAcquire();
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
        double hardwarePressure =
                core != null ? core.cpuSnapshots()[this.cpuId].pressure()
                        : 0.0;

        double base = Math.max(vegasPressure, hardwarePressure);
        return clampDouble(base, 0.0, 1.0);
    }

    @Override
    public ExecutionManager clone(CloneConfig cloneConfig) {
        return new ExecutionManager(this.config.clone(cloneConfig));
    }

    @Override
    public void errorChannel(Publisher<Failure> errorFlux) {
        errorFlux.subscribe(new CoreSubscriber<>() {
            @Override
            public void onSubscribe(@NonNull Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Failure failure) {
                ExecutionManager.this.logger.error("Execution failure", failure.exception());
            }

            @Override
            public void onError(Throwable throwable) {
                ExecutionManager.this.logger.error("Error", throwable);
            }

            @Override
            public void onComplete() {

            }
        });
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
        public final long lowWaterMark = bufferSize >> 2;

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
