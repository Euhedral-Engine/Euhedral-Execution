package euhedral.io;

import static euhedral.io.utils.MathFunctions.clampDouble;
import static euhedral.io.utils.MathFunctions.clampLong;
import static euhedral.io.utils.MathFunctions.log2;

import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.SystemInfo.CpuCacheLayout;
import euhedral.hardware_utils.SystemInfo.CpuInfo;
import euhedral.hardware_utils.ThreadTools;
import euhedral.io.SlotManagerSMTBuddy.SMTState;
import euhedral.io.control_plane.CloneConfig;
import euhedral.io.flow_control.DirectOutputFlux;
import euhedral.io.flow_control.IngestSequencer;
import euhedral.io.flow_control.IngestSequencer.WakeHook;
import euhedral.io.flow_control.LockFreeSink;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.DummyInitFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.interfaces.SlotManager;
import euhedral.hardware_utils.common.SystemUtilization.CoreSnapshot;
import euhedral.hardware_utils.common.SystemUtilization.CpuSnapshot;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;
import euhedral.io.hardware_utils.pinning.PinnedThreadExecutor;
import euhedral.io.utils.ObjectSizer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

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
public class DefaultSlotManager implements SlotManager {

    public final int cpuId;

    @Getter
    protected final Config config;
    protected final Metrics metrics;
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

    protected volatile long avgLatency;
    protected volatile long currentRate;
    protected volatile long currentConcurrency;
    protected volatile long effectiveConcurrencyLimit;

    protected volatile boolean drainMode = false;
    protected volatile CoreSnapshot coreSnapshot = null;

    protected volatile IngestSequencer ingest = null;
    protected volatile int inFlight = 0;

    protected long upstreamCount = 0;

    protected WakeHook wakeHook;
    private Thread cycleThread;

    public DefaultSlotManager(@NonNull Config config) {
        this.config = config;

        int bufferSize = (int) Math.min(Long.highestOneBit((SystemInfo.DEFAULT_L1 - 1) << 1),
                Integer.MAX_VALUE);
        if (config.cloneConfig != null) {
            CpuCacheLayout layout = SystemInfo.getCacheLayout(config.cloneConfig.getCpuSet()[0]);
            long temp = layout.bytesL1();
            if (config.cloneConfig.getCpuSet().length != layout.sharesL1()) {
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
        this.maxUpdateInterval = Integer.highestOneBit(Math.max(config.maxUpdateInterval, 2));

        this.state = new CycleState();
        this.buddyState = new SMTState(executionLatency, bufferWrapper.arrivalLatencyRecorder,
                config.idleCyclePolicy.maxParkTime.toNanos());

        this.buddy = new SlotManagerSMTBuddy(bufferWrapper, buddyState);

        this.completeSink =
                new LockFreeSink(new MpscUnboundedXaddArrayQueue<>(bufferSize, 4), frame -> {
                    this.inFlight--;
                    state.receivingOrderedWork = upstreamCount == 1 && frame.isOrdered();
                    state.completed++;
                    frame.doFinally();
                }, this::recordCompletion);

        this.currentRate = config.initialConcurrency;
        this.currentConcurrency = Math.max(1, config.initialConcurrency);
        this.effectiveConcurrencyLimit = config.initialConcurrency;

        if (config.cloneConfig == null) {
            this.cpuId = -1;
            this.pinnedExecutor = null;
            this.logger = LoggerFactory.getLogger(DefaultSlotManager.class);
        } else {
            int[] cpus = config.cloneConfig.getCpuSet();
            this.cpuId = cpus[0];
            String name = config.cloneConfig.shardName() + "-DefaultSlotManager-"
                    + config.cloneConfig.coreId();

            this.pinnedExecutor =
                    PinnedThreadExecutor.getOrSetIfAbsent(cpus[0], name, Thread.MAX_PRIORITY,
                            false);
            this.logger = LoggerFactory.getLogger(name);
        }

        this.metrics = new Metrics(config.meterRegistry, config, () -> inFlight, () -> avgLatency,
                () -> currentConcurrency, () -> currentRate, this::getPressure);

        outputFlux = new DirectOutputFlux(buffer, frame -> {
            if ((state.dispatches++ & state.updateIntervalMask) == 0) {
                frame.setStartNs(System.nanoTime());
            } else {
                frame.setStartNs(0);
            }
            frame.setCompletionSink(completeSink);
        });

        this.shutdownHook = new Thread(this::close);
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    protected void recordCompletion(AbstractFrame frame) {
        if (!frame.isCancelledExecution() && frame.getStartNs() > 0) {
            long now = System.nanoTime();
            executionLatency.record(now, now - frame.getStartNs(), false);
        }
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            if (ingest != null) {
                ingest.removeThread(cycleThread);
                ingest.close();
            }
            buddy.close();
            if (cycleThread != null) {
                try {
                    LockSupport.unpark(cycleThread);
                    cycleThread.interrupt();
                    cycleThread.join(500);
                } catch (Exception ignored) {
                }
                cycleThread = null;
            }
            dumpLocks();
            AbstractFrame frame;
            while ((frame = buffer.poll()) != null) {
                frame.kill();
            }
            buffer.clear();
            metrics.close();
            pinnedExecutor.close();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (Exception ignored) {

            }
        }
        logger.info("Closed");
    }

    public void dumpLocks() {
        if (pinnedExecutor != null) {
            pinnedExecutor.close();
        }
    }

    @Override
    public void firstTouch() {
        for (int i = 0; i < bufferSize * 2; i++) {
            buffer.add(DummyInitFrame.INSTANCE);
        }
        buffer.clear();
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            CloneConfig cloneConfig = config.cloneConfig;
            if (cloneConfig != null) {
                if (pinnedExecutor.isShutdown()) {
                    pinnedExecutor.start(config.cloneConfig.shardName() + "-DefaultSlotManager-"
                            + config.cloneConfig.coreId(), Thread.MAX_PRIORITY, false);
                }

                pinnedExecutor.execute(() -> {
                    this.cycleThread = Thread.currentThread();

                    CpuInfo origin = ThreadTools.getOrigin();
                    if (cloneConfig.coreId() != origin.core()) {
                        logger.warn("Attempted to pin to CPU: {} Core: {} but was assigned: {}",
                                cpuId, cloneConfig.coreId(), origin);
                    } else {
                        logger.info("Pinned to Core {} CPU {}", cloneConfig.coreId(), cpuId);
                    }
                    ThreadTools.setTimerResolution(1);
                    if (config.enableSMT && cloneConfig.getCpuSet().length > 1) {
                        this.buddy.start(cloneConfig.getCpuSet()[1],
                                config.cloneConfig.shardName() + "-DefaultSlotManager-SMT-"
                                        + config.cloneConfig.coreId(), Thread.MAX_PRIORITY, false);
                        state.smtMode = true;
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
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                state.receivingOrderedWork = false;
                completeSink.drain();
                if (state.completed > state.updateIntervalMask) {
                    updateLimits();
                    state.completed = 0;
                }

                state.lastActiveNs = System.nanoTime();
                long remaining = dispatchWaitNs - state.lastActiveNs;
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
                    state.idleRecorder.record(System.nanoTime(), 0, false);
                    state.rests >>>= 1;

                    if ((processed & 127) == 0) {
                        Thread.onSpinWait();
                    }
                    continue;
                }

                if (ingest == null) {
                    idleSpin(5);
                    continue;
                }

                long ingestCount = ingest.getCount();

                if (buddyState.bufferCount.get() > state.lowWaterMark && ingestCount == 0) {
                    idleSpin(state.rests);
                    continue;
                }

                long newUpCount = ingest.getUpstreamCount();
                if (upstreamCount != newUpCount) {
                    state.idleRecorder.reset(false);
                    state.rests = 0;
                    upstreamCount = newUpCount;
                }
                // This is usually hit when there are producers present but nothing is flowing
                else if (processed == 0 && (state.rests & 15) != 0 && upstreamCount > 0
                        && !state.receivingOrderedWork && ingestCount == 0
                        && state.lastEmptyNs - state.lastActiveNs > 10 * state.maxParkNs) {
                    idleSpin(Math.min(15, state.rests));
                    continue;
                }

                state.lastEmptyNs = System.nanoTime();
                if (!state.smtMode && state.lastEmptyNs > buddyState.demandWaitNs) {
                    this.buddy.doStuff();
                }

                state.rests++;
            }
        } catch (Throwable e) {
            logger.error("Error", e);
        } finally {
            running.set(false);
        }
    }

    protected int dispatch() {
        int bufferCount = buddyState.bufferCount.get();
        if (bufferCount == 0) {
            return 0;
        }

        long currentConcurrency = this.currentConcurrency;
        long quota = Math.max(0, currentConcurrency - this.inFlight);
        quota = drainMode ? bufferCount : quota;

        int processed = 0;
        if (quota > 0 && bufferCount > 0) {
            processed = outputFlux.drain(quota);
            if (processed > 0) {
                buddyState.bufferCount.addAndGet(-processed);
                this.inFlight += processed;
            }
        }
        return processed;
    }

    protected void updateLimits() {
        FlowSnapshot flowSnapshot = executionLatency.getFlowSnapshot();
        executionLatency.refreshSnapshot(flowSnapshot, false);

        double avgVariance = flowSnapshot.unitVariation;
        int updateInterval = state.updateIntervalMask + 1;
        double scaledVariance = avgVariance * updateInterval;

        if (scaledVariance >= updateInterval) {
            state.updateIntervalMask = Math.min(updateInterval << 1, maxUpdateInterval) - 1;
        } else if (scaledVariance <= (updateInterval >>> 1)) {
            state.updateIntervalMask = Math.max(2, updateInterval >>> 1) - 1;
        }

        avgLatency = (long) (flowSnapshot.avgUnits + avgVariance);

        double queueEstimate =
                executionLatency.getVegasQueueEstimate(flowSnapshot, flowSnapshot.avgUnits,
                        currentConcurrency);

        long ideal = flowSnapshot.throughputNs * updateInterval;
        updateEffectiveConcurrencyLimit(ideal);
        updateConcurrency(ideal, queueEstimate);
    }

    protected void updateEffectiveConcurrencyLimit(long ideal) {
        CpuSnapshot cpuSnapshot = coreSnapshot.cpuSnapshots()[cpuId];

        ideal = Math.max(ideal, currentConcurrency);

        long adaptiveCap = ideal << 2;

        double pressure = cpuSnapshot.pressure();
        pressure *= isPCore ? 0.5 : 0.7;
        adaptiveCap = (long) (adaptiveCap * (1.0 - pressure));

        long cpuCount = cpuSnapshot.globalCpuCount();
        long hardwareMax = cpuCount * bufferSize;

        this.effectiveConcurrencyLimit =
                clampLong(adaptiveCap, config.initialConcurrency, hardwareMax);
    }

    protected void updateConcurrency(long ideal, double queueEstimate) {
        if (drainMode) {
            this.currentConcurrency = effectiveConcurrencyLimit;
            return;
        }

        long current = currentConcurrency;

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

        FlowSnapshot flowSnapshot = executionLatency.getFlowSnapshot();
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

        if (Math.signum(combined) != Math.signum(state.concurrencyFactor)) {
            state.stabilityCounter = 0;
        } else {
            state.stabilityCounter++;
        }
        state.concurrencyFactor = combined;

        if (Math.abs(combined) < gain || state.stabilityCounter < 3) {
            return;
        }

        long next = (long) (current * (1.0 + combined * gain));

        this.currentConcurrency = clampLong(next, 1, effectiveConcurrencyLimit);
    }

    protected long calculateDispatchWaitNs(long nowNs) {
        FlowSnapshot exec = executionLatency.getFlowSnapshot();

        double avgLatency = Math.max(exec.avgUnits, 1.0);
        double avgVariance = exec.unitVariation;

        double queuePressure =
                executionLatency.getVegasQueueEstimate(exec, exec.avgUnits, currentConcurrency);

        CpuSnapshot cpu = coreSnapshot.cpuSnapshots()[cpuId];
        double cpuPressure = cpu.pressure();
        double cpuThrottle = cpuPressure * (isPCore ? 0.5 : 0.7);

        long baseIntervalNs = (long) avgLatency;

        double queueFactor = 1.0 + (queuePressure / (double) (Math.max(currentConcurrency, 1)));
        double variability = 1.0 + Math.min(1.0, avgVariance * 0.5);

        long interval = (long) (baseIntervalNs * queueFactor * variability);

        // CPU backpressure
        interval = (long) (interval * (1.0 + cpuThrottle));
        interval = clampLong(interval, 0, state.maxParkNs);

        return nowNs + interval;
    }

    protected void idleSpin(long parks) {
        long now = System.nanoTime();
        state.idleRecorder.record(now, 1, false);

        double idleRatio = state.idleRecorder.getRollingAverage(now, false);
        if (idleRatio <= config.idleCyclePolicy.spinThreshold) {
            Thread.onSpinWait();
        } else if (idleRatio <= config.idleCyclePolicy.yieldThreshold) {
            Thread.yield();
        } else {
            while (parks-- > 0) {
                park(state.maxParkNs);

                if (this.buddyState.bufferCount.get() > 0) {
                    break;
                }

                if (ingest != null && !state.smtMode) {
                    if (upstreamCount != ingest.getUpstreamCount()) {
                        break;
                    }

                    int bufferCount = buddyState.bufferCount.get();
                    if (upstreamCount == 0) {
                        long count = ingest.drain(bufferWrapper, bufferSize - bufferCount, 0);
                        if (count > 0) {
                            buddyState.bufferCount.addAndGet((int) count);
                            break;
                        }
                    }

                    if (ingest.getCount() >= (bufferSize >> 3)) {
                        long count = ingest.drain(bufferWrapper, bufferSize - bufferCount, 0);
                        buddyState.bufferCount.addAndGet((int) count);
                        break;
                    }
                }
            }
        }
    }

    protected final void park(long parkNs) {
        if (wakeHook != null) {
            wakeHook.parked = true;
        }
        LockSupport.parkNanos(parkNs);
        if (wakeHook != null) {
            wakeHook.parked = false;
        }
    }

    @Override
    public Publisher<? extends AbstractFrame> process(Publisher<? extends AbstractFrame> flux) {
        ingest(flux);
        return output();
    }

    @Override
    public void ingest(Publisher<? extends AbstractFrame> frameFlux) {
        if (frameFlux instanceof IngestSequencer sequencer && ingest == null) {
            ingest = sequencer;
            buddy.setIngest(ingest);
            wakeHook = new WakeHook(cycleThread);
            sequencer.setWakeHook(wakeHook);
            LockSupport.unpark(cycleThread);
        }
    }

    @Override
    public Publisher<? extends AbstractFrame> output() {
        return outputFlux;
    }

    @Override
    public boolean isStarted() {
        return running.get();
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        this.coreSnapshot = snapshot;
    }

    @Override
    public double getPressure() {
        long alpha = Math.max(3, 3 * Math.max(3, log2(currentConcurrency)));
        long beta = Math.max(6, 6 * alpha);

        FlowSnapshot snapshot = executionLatency.getFlowSnapshot();
        executionLatency.refreshSnapshot(snapshot, true);
        double queueEstimate = executionLatency.getVegasQueueEstimate(snapshot,
                snapshot.avgUnits + snapshot.unitVariation, currentConcurrency);

        double vegasPressure = queueEstimate / beta;

        double hardwarePressure =
                coreSnapshot != null ? coreSnapshot.cpuSnapshots()[cpuId].pressure() : 0.0;

        double base = Math.max(vegasPressure, hardwarePressure);
        return clampDouble(base, 0.0, 1.0);
    }

    @Override
    public DefaultSlotManager clone(CloneConfig cloneConfig) {
        return new DefaultSlotManager(config.clone(cloneConfig));
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
                logger.error("Execution failure", failure.exception());
            }

            @Override
            public void onError(Throwable throwable) {
                logger.error("Error", throwable);
            }

            @Override
            public void onComplete() {

            }
        });
    }

    @Override
    public boolean isDrained() {
        return inFlight == 0 && buffer.isEmpty();
    }

    @Override
    public void setDrainMode(boolean value) {
        this.drainMode = value;
    }

    public static final class Metrics implements AutoCloseable {

        public final MeterRegistry registry;
        private final List<Meter> meters = new ArrayList<>();

        public Metrics(MeterRegistry registry, Config config, Supplier<Integer> inFlight,
                Supplier<Long> latency, Supplier<Long> currentConcurrency,
                Supplier<Long> currentRate, Supplier<Double> pressure) {
            this.registry = registry;

            if (registry != null && config.cloneConfig != null) {
                String coreId = String.valueOf(config.cloneConfig.coreId());

                meters.add(Gauge.builder(config.metricPrefix + ".execution.latency", latency)
                        .description("Average time for execution of work.").tag("core", coreId)
                        .baseUnit("nanoseconds").register(registry));

                meters.add(Gauge.builder(config.metricPrefix + ".execution.concurrency.current",
                                currentConcurrency).description("Current adaptive concurrency limit")
                        .tag("core", coreId).register(registry));

                meters.add(
                        Gauge.builder(config.metricPrefix + ".execution.inflight.count", inFlight)
                                .description("Number of frames being executed").tag("core", coreId)
                                .register(registry));

                meters.add(Gauge.builder(config.metricPrefix + ".execution.throughput", currentRate)
                        .description("Current execution rate (execution/sec)").tag("core", coreId)
                        .register(registry));

                meters.add(Gauge.builder(config.metricPrefix + ".execution.pressure", pressure)
                        .description(
                                "Combined signal of reported hardware and calculated execution pressure")
                        .tag("core", coreId).register(registry));
            }
        }

        @Override
        public void close() {
            meters.forEach(Meter::close);
            meters.clear();
        }
    }

    public record Config(CloneConfig cloneConfig, int initialConcurrency, int maxUpdateInterval,
                         boolean enableSMT, IdleCyclePolicy idleCyclePolicy,
                         MeterRegistry meterRegistry, String metricPrefix)
            implements CloneableObject {

        public static Config powerSavingDefault(MeterRegistry meterRegistry, String metricPrefix) {
            return new Config(null, 1_024, 256, false, IdleCyclePolicy.POWER_SAVING, meterRegistry,
                    metricPrefix);
        }

        public static Config balancedDefault(MeterRegistry meterRegistry, String metricPrefix) {
            return new Config(null, 4_096, 512, true, IdleCyclePolicy.DEFAULT, meterRegistry,
                    metricPrefix);
        }

        /// This default uses a maxUpdateInterval of 1024. Higher intervals reduce unneeded
        /// recomputation of limits and execution latency recording. They also reduce sensitivity to
        /// micro jitter.
        public static Config lowLatencyDefault(MeterRegistry meterRegistry, String metricPrefix) {
            return new Config(null, 4_096, 1024, false, IdleCyclePolicy.LOW_LATENCY, meterRegistry,
                    metricPrefix);
        }

        @Override
        public Config clone(CloneConfig cloneConfig) {
            MeterRegistry meterRegistry = null;
            if (cloneConfig != null) {
                meterRegistry = cloneConfig.meterRegistry();
            }
            return new Config(cloneConfig, initialConcurrency, maxUpdateInterval, enableSMT,
                    idleCyclePolicy, meterRegistry, metricPrefix);
        }

        @Override
        public void close() {
        }

        /// Defines how the DefaultSlotManager will react when it doesn't process work in a cycle.
        /// Setting the threshold values higher than 1.0 disables them.
        ///
        /// @param spinThreshold  Upper limit defined by idleCyles / totalCycles for using
        /// Thread.onSpinWait()
        /// @param yieldThreshold Upper limit defined by idleCyles / totalCycles for using
        /// Thread.yield()
        /// @param maxParkTime    Max duration of each LockSupport.parkNanos()
        public record IdleCyclePolicy(double spinThreshold, double yieldThreshold,
                                      Duration maxParkTime) {

            public static IdleCyclePolicy DEFAULT =
                    new IdleCyclePolicy(0.25, 0.60, Duration.ofMillis(10));
            public static IdleCyclePolicy LOW_LATENCY =
                    new IdleCyclePolicy(0.40, 0.80, Duration.ofNanos(20_000));
            public static IdleCyclePolicy POWER_SAVING =
                    new IdleCyclePolicy(2.0, 2.0, Duration.ofNanos(100_000));
        }
    }

    protected class CycleState {

        public final long maxParkNs = config.idleCyclePolicy.maxParkTime.toNanos();
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
