package euhedral.io;

import static euhedral.io.utils.MathFunctions.clampDouble;
import static euhedral.io.utils.MathFunctions.clampLong;
import static euhedral.io.utils.MathFunctions.log2;

import euhedral.io.control_plane.CloneConfig;
import euhedral.io.flow_control.DirectOutputFlux;
import euhedral.io.flow_control.IngestSequencer;
import euhedral.io.flow_control.IngestSequencer.WakeHook;
import euhedral.io.flow_control.LockFreeSink;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.frames.DummyInitFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.interfaces.SlotManager;
import euhedral.io.resource_monitoring.NumaMapper;
import euhedral.io.resource_monitoring.NumaMapper.OriginLocation;
import euhedral.io.resource_monitoring.SystemUtilization.CoreSnapshot;
import euhedral.io.resource_monitoring.SystemUtilization.CpuSnapshot;
import euhedral.io.utils.DemandOptimizer;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.FlowRecorder.FlowSnapshot;
import euhedral.io.utils.PinnedThreadExecutor;
import euhedral.io.utils.ThreadTimerResolution;
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

    @Getter
    protected final Config config;
    protected final Metrics metrics;
    protected final Logger logger;
    protected final AtomicBoolean running = new AtomicBoolean(false);

    protected final FlowRecorder executionLatency;

    protected final SpscUnboundedUnpaddedArrayQueue<AbstractFrame> buffer;
    protected final int bufferSize;
    protected final DrainBuffer bufferWrapper;

    protected final LockFreeSink completeSink;
    protected final int maxUpdateInterval;

    private final Thread shutdownHook;
    private final int cpuId;
    @Getter
    private final PinnedThreadExecutor pinnedExecutor;

    private final DirectOutputFlux outputFlux;

    protected volatile long avgLatency;
    protected volatile long currentRate;
    protected volatile long currentConcurrency;
    protected volatile long effectiveConcurrencyLimit;

    protected volatile boolean parked = false;
    protected volatile boolean drainMode = false;
    protected volatile CoreSnapshot coreSnapshot = null;

    protected volatile IngestSequencer ingest = null;
    protected volatile int inFlight = 0;

    private int bufferCount = 0;
    private int updateIntervalMask = 1;
    private long dispatches = 0;
    private int completed = 0;
    private double concurrencyFactor = 1.0;
    private int stabilityCounter = 0;

    private long upstreamCount = 0;
    private boolean receivingOrderedWork = false;

    private WakeHook wakeHook;
    private Thread cycleThread;

    public DefaultSlotManager(@NonNull Config config) {
        this.config = config;

        int bufferSize = Integer.highestOneBit((config.bufferSize - 1) << 1);
        bufferSize = Math.max(bufferSize, 64);

        this.buffer = new SpscUnboundedUnpaddedArrayQueue<>(bufferSize);
        this.completeSink = new LockFreeSink(new MpscUnboundedXaddArrayQueue<>(bufferSize, 4),
                frame -> {
                    this.inFlight--;
                    this.receivingOrderedWork = upstreamCount == 1 && frame.isOrdered();
                    this.completed++;
                    frame.doFinally();
                }, this::recordCompletion);
        this.bufferSize = bufferSize;
        this.bufferWrapper = new DrainBuffer(buffer, false);

        this.currentRate = config.initialConcurrency;
        this.currentConcurrency = Math.max(1, config.initialConcurrency);
        this.effectiveConcurrencyLimit = config.initialConcurrency;

        this.executionLatency = new FlowRecorder();
        this.maxUpdateInterval = Integer.highestOneBit(Math.max(config.maxUpdateInterval, 2));

        if (config.cloneConfig == null) {
            this.cpuId = -1;
            this.pinnedExecutor = null;
            this.logger = LoggerFactory.getLogger(DefaultSlotManager.class);
        } else {
            int[] cpus = config.cloneConfig.getCpuSet();
            this.cpuId = cpus[0];
            this.pinnedExecutor = PinnedThreadExecutor.getOrSetIfAbsent(cpus[0],
                    config.cloneConfig.shardName() + "-DefaultSlotManager-"
                            + config.cloneConfig.coreId(),
                    Thread.MAX_PRIORITY, false);
            this.logger = LoggerFactory.getLogger(
                    config.cloneConfig.shardName() + "-DefaultSlotManager");
        }

        this.metrics = new Metrics(config.meterRegistry, config, () -> inFlight, () -> avgLatency,
                () -> currentConcurrency,
                () -> currentRate);

        outputFlux = new DirectOutputFlux(buffer, frame -> {
            if ((dispatches++ & updateIntervalMask) == 0) {
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
                    pinnedExecutor.start(
                            config.cloneConfig.shardName() + "-DefaultSlotManager-"
                                    + config.cloneConfig.coreId(),
                            Thread.MAX_PRIORITY, false);
                }

                pinnedExecutor.execute(() -> {
                    this.cycleThread = Thread.currentThread();

                    OriginLocation origin = NumaMapper.locateMe();
                    if (cloneConfig.coreId() != origin.core()) {
                        logger.warn("Attempted to pin to CPU: {} Core: {} but was assigned: {}",
                                cpuId, cloneConfig.coreId(), origin);
                    } else {
                        logger.info("Pinned to Core {} CPU {}", cloneConfig.coreId(), cpuId);
                    }
                    ThreadTimerResolution.setResolution(1);
                    cycle();
                });
            } else {
                cycle();
            }
        }
    }

    private void cycle() {
        try {
            final long lowWaterMark = bufferSize >> 2;

            FlowSnapshot fillSnapshot = null;
            FlowSnapshot fillBytesSnapshot = null;
            FlowSnapshot drainSnapshot = null;
            final FlowSnapshot arrivalLatencySnapshot = bufferWrapper.arrivalLatencyRecorder.getFlowSnapshot();
            final FlowRecorder idleRecorder = new FlowRecorder();

            long requests = 0;
            long demandWaitNs = 0;
            long lastRequestNs = 0;

            long maxParkNs = config.idleCyclePolicy.maxParkTime.toNanos();
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                receivingOrderedWork = false;
                completeSink.drain();
                if (completed > updateIntervalMask) {
                    updateLimits();
                    completed = 0;
                }

                long currentConcurrency = this.currentConcurrency;
                long quota = Math.max(0, currentConcurrency - this.inFlight);
                quota = drainMode ? bufferCount : quota;

                int processed = 0;
                if (quota > 0 && bufferCount > 0) {
                    processed = outputFlux.drain(quota);
                    if (processed > 0) {
                        this.bufferCount -= processed;
                        this.inFlight += processed;

                        requests >>>= 1;
                        idleRecorder.record(System.nanoTime(), 0, false);

                        if ((processed & 127) == 0) {
                            Thread.onSpinWait();
                        }
                        continue;
                    }
                }

                if (ingest == null) {
                    idleSpin(idleRecorder, 5, maxParkNs);
                    continue;
                }
                if (fillSnapshot == null) {
                    fillSnapshot = ingest.getFillRecorder().getFlowSnapshot();
                    fillBytesSnapshot = ingest.getFillBytesRecorder().getFlowSnapshot();
                    drainSnapshot = ingest.getDrainRecorder().getFlowSnapshot();
                }

                long ingestCount = ingest.getCount();

                if (bufferCount > lowWaterMark && ingestCount == 0) {
                    idleSpin(idleRecorder, requests, maxParkNs);
                    continue;
                }

                ingest.getFillRecorder().refreshSnapshot(fillSnapshot, true);
                long newUpCount = ingest.getUpstreamCount();
                if (upstreamCount != newUpCount) {
                    idleRecorder.reset(false);
                    requests = 0;
                    upstreamCount = newUpCount;
                } else if (processed == 0 && (requests & 15) != 0 && upstreamCount > 0
                        && !receivingOrderedWork
                        && ingestCount == 0
                        && lastRequestNs - fillSnapshot.lastRecordingTimeNs > 10 * maxParkNs) {
                    idleSpin(idleRecorder, requests, maxParkNs);
                    continue;
                }

                ingest.getFillBytesRecorder().refreshSnapshot(fillBytesSnapshot, true);
                ingest.getDrainRecorder().refreshSnapshot(drainSnapshot, true);

                double drainRate = drainSnapshot.avgRate;
                double drainRateVar = drainSnapshot.rateVariation;
                double arrivalLatencyNs = arrivalLatencySnapshot.avgUnits;
                double arrivalLatencyVar = arrivalLatencySnapshot.unitVariation;
                double avgFrameSize = fillBytesSnapshot.avgUnits + fillBytesSnapshot.unitVariation;

                long demand = DemandOptimizer.getDemand(drainRate, arrivalLatencyNs,
                        drainRateVar, arrivalLatencyVar, bufferCount + ingestCount,
                        (long) avgFrameSize, ingest.getMaxQueuedBytes());

                long maxFill = bufferSize - bufferCount;
                if (demand < maxFill) {
                    demand += maxFill - bufferCount;
                }

                long nowNs = System.nanoTime();

                if (!receivingOrderedWork && nowNs < demandWaitNs) {
                    demand = 0;
                } else if (!receivingOrderedWork) {
                    boolean warmedUp = ingest.getFillRecorder().getRollingSum() > bufferSize
                            && fillSnapshot.avgInterval > 0
                            && fillSnapshot.avgUnits > 0;

                    if (warmedUp) {
                        FlowSnapshot execSnapshot = executionLatency.getFlowSnapshot();
                        executionLatency.refreshSnapshot(execSnapshot, true);

                        double fillRate = fillSnapshot.avgRate;
                        double execLatency = execSnapshot.avgUnits;

                        double execRate = 1.0 / Math.max(execLatency, 1.0);

                        if (fillRate < execRate * 0.5) {
                            demandWaitNs = nowNs + (long) fillSnapshot.avgInterval;
                        } else {
                            double fillInterval = fillSnapshot.avgInterval
                                    + fillSnapshot.intervalVariation;
                            fillInterval = Math.max(fillInterval, 1_000);

                            double avgFill = fillSnapshot.avgUnits
                                    + fillSnapshot.unitVariation;
                            avgFill = Math.max(avgFill, 64);

                            double intervalCount = maxFill / avgFill;

                            long maxWaitNs = (long) (execLatency * bufferSize / 2);
                            maxWaitNs = Math.min(maxWaitNs, maxParkNs);

                            long fillWait = (long) (intervalCount * fillInterval);
                            demandWaitNs = nowNs + Math.min(maxWaitNs, fillWait);
                        }
                    } else {
                        demandWaitNs = 0;
                    }
                }

                lastRequestNs = nowNs;
                int drained = (int) ingest.drain(bufferWrapper, (int) maxFill, demand);
                bufferCount += drained;
                requests++;
            }
        } catch (Throwable e) {
            logger.error("Error", e);
        } finally {
            running.set(false);
            parked = false;
        }
    }

    protected void updateLimits() {
        FlowSnapshot flowSnapshot = executionLatency.getFlowSnapshot();
        executionLatency.refreshSnapshot(flowSnapshot, false);

        double avgVariance = flowSnapshot.unitVariation;
        int updateInterval = updateIntervalMask + 1;
        double scaledVariance = avgVariance * updateInterval;

        if (scaledVariance >= updateInterval) {
            updateIntervalMask = Math.min(updateInterval << 1, maxUpdateInterval) - 1;
        } else if (scaledVariance <= (updateInterval >>> 1)) {
            updateIntervalMask = Math.max(2, updateInterval >>> 1) - 1;
        }

        avgLatency = (long) (flowSnapshot.avgUnits + avgVariance);

        double queueEstimate = executionLatency.getVegasQueueEstimate(flowSnapshot,
                flowSnapshot.avgUnits,
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
        adaptiveCap = (long) (adaptiveCap * (1.0 - pressure * 0.5));

        long cpuCount = cpuSnapshot.globalCpuCount();
        long hardwareMax = cpuCount * 4096;

        this.effectiveConcurrencyLimit = Math.max(
                config.initialConcurrency,
                Math.min(adaptiveCap, hardwareMax)
        );
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

        if (Math.signum(combined) != Math.signum(concurrencyFactor)) {
            stabilityCounter = 0;
        } else {
            stabilityCounter++;
        }
        concurrencyFactor = combined;

        if (Math.abs(combined) < gain || stabilityCounter < 3) {
            return;
        }

        long next = (long) (current * (1.0 + combined * gain));

        this.currentConcurrency = clampLong(next, 1, effectiveConcurrencyLimit);
    }

    protected void idleSpin(FlowRecorder idleRecorder, long parks, long maxParkNs) {
        long now = System.nanoTime();
        idleRecorder.record(now, 1, false);

        double idleRatio = idleRecorder.getRollingAverage(now, false);
        if (idleRatio <= config.idleCyclePolicy.spinThreshold) {
            Thread.onSpinWait();
        } else if (idleRatio <= config.idleCyclePolicy.yieldThreshold) {
            Thread.yield();
        } else {
            while (parks-- > 0) {
                park(maxParkNs);

                if (upstreamCount != ingest.getUpstreamCount()) {
                    break;
                }

                if (upstreamCount == 0) {
                    long count = ingest.drain(bufferWrapper, bufferSize - bufferCount, 0);
                    if (count > 0) {
                        bufferCount += (int) count;
                        break;
                    }
                }

                if (ingest.getCount() >= (bufferSize >> 3)) {
                    long count = ingest.drain(bufferWrapper, bufferSize - bufferCount, 0);
                    bufferCount += (int) count;
                    break;
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
                snapshot.avgUnits + snapshot.unitVariation,
                currentConcurrency);

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
                Supplier<Long> latency,
                Supplier<Long> currentConcurrency, Supplier<Long> currentRate) {
            this.registry = registry;

            if (registry != null && config.cloneConfig != null) {
                String coreId = String.valueOf(config.cloneConfig.coreId());

                meters.add(Gauge.builder(config.metricPrefix + ".execution.latency", latency)
                        .description("Average time of execution of work.")
                        .tag("core", coreId)
                        .baseUnit("nanoseconds").register(registry));

                meters.add(Gauge.builder(config.metricPrefix + ".execution.concurrency.current",
                                currentConcurrency).description("Current adaptive concurrency limit")
                        .tag("core", coreId)
                        .register(registry));

                meters.add(
                        Gauge.builder(config.metricPrefix + ".execution.inflight.count", inFlight)
                                .description("Number of frames being executed")
                                .tag("core", coreId)
                                .register(registry));

                meters.add(Gauge.builder(config.metricPrefix + ".execution.throughput", currentRate)
                        .description("Current execution rate (execution/sec)")
                        .tag("core", coreId)
                        .register(registry));
            }
        }

        @Override
        public void close() {
            meters.forEach(Meter::close);
            meters.clear();
        }
    }

    public record Config(CloneConfig cloneConfig, int initialConcurrency, int bufferSize,
                         int maxUpdateInterval,
                         IdleCyclePolicy idleCyclePolicy,
                         MeterRegistry meterRegistry, String metricPrefix) implements
            CloneableObject {

        public static Config balancedDefault(
                MeterRegistry meterRegistry, String metricPrefix) {
            return new Config(null, 4_096, 4_096, 1024, IdleCyclePolicy.DEFAULT,
                    meterRegistry, metricPrefix);
        }

        public static Config lowLatencyDefault(
                MeterRegistry meterRegistry, String metricPrefix) {
            return new Config(null, 4_096, 4_096, 512, IdleCyclePolicy.LOW_LATENCY,
                    meterRegistry, metricPrefix);
        }

        @Override
        public Config clone(CloneConfig cloneConfig) {
            MeterRegistry meterRegistry = null;
            if (cloneConfig != null) {
                meterRegistry = cloneConfig.meterRegistry();
            }
            return new Config(cloneConfig, initialConcurrency, bufferSize,
                    maxUpdateInterval,
                    idleCyclePolicy,
                    meterRegistry, metricPrefix);
        }

        @Override
        public void close() {
        }

        /**
         * Defines how the DefaultSlotManager will react when it doesn't process work in a cycle.
         * Setting these values higher than 1.0 disables them.
         *
         */
        public record IdleCyclePolicy(double spinThreshold, double yieldThreshold,
                                      Duration maxParkTime) {

            public static IdleCyclePolicy DEFAULT = new IdleCyclePolicy(0.25, 0.60,
                    Duration.ofMillis(10));
            public static IdleCyclePolicy LOW_LATENCY = new IdleCyclePolicy(0.40, 0.80,
                    Duration.ofNanos(20_000));
        }
    }
}
