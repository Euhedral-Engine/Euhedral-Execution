package euhedral.io;

import euhedral.io.control_plane.CloneConfig;
import euhedral.io.flow_control.DirectOutputFlux;
import euhedral.io.flow_control.IngestSequencer;
import euhedral.io.flow_control.IngestSequencer.WakeHook;
import euhedral.io.frames.AbstractFrame;
import euhedral.io.interfaces.CloneableObject;
import euhedral.io.interfaces.SlotManager;
import euhedral.io.utils.DemandOptimizer;
import euhedral.io.utils.DrainBuffer;
import euhedral.io.utils.FlowRecorder;
import euhedral.io.utils.PinnedThreadExecutor;
import euhedral.io.utils.SystemUtilization.CoreSnapshot;
import euhedral.io.utils.SystemUtilization.CpuSnapshot;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;

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

    protected final AtomicLong recentErrors = new AtomicLong(0);
    protected final Sinks.Many<Object> failureBroadcast = Sinks.many().multicast()
            .onBackpressureBuffer();

    protected final FlowRecorder executionLatency;
    protected final AtomicInteger inFlight = new AtomicInteger(0);

    protected final SpscUnboundedUnpaddedArrayQueue<AbstractFrame> buffer;
    protected final long chunkSize;
    protected final DrainBuffer bufferWrapper;

    protected final Sinks.Many<AbstractFrame> completeSink = Sinks.many().unicast()
            .onBackpressureBuffer(new MpscUnboundedXaddArrayQueue<>(2048, 4));
    private final Thread shutdownHook;
    private final int cpuId;
    @Getter
    private final PinnedThreadExecutor pinnedExecutor;
    private final Sinks.Many<Failure> errorSink = Sinks.many().unicast()
            .onBackpressureBuffer(new MpscUnboundedXaddArrayQueue<>(1024));

    private final DirectOutputFlux outputFlux;

    protected volatile long avgLatency;
    protected volatile long currentRate;
    protected volatile long currentConcurrency;
    protected volatile long effectiveConcurrencyLimit;

    protected volatile boolean parked = false;
    protected volatile boolean drainMode = false;
    protected volatile CoreSnapshot coreSnapshot = null;

    protected volatile IngestSequencer ingest = null;

    private int bufferCount = 0;
    private long upstreamCount = 0;
    private long executions = 0;
    private double concurrencyFactor = 1.0;
    private int stabilityCounter = 0;

    private CircuitBreaker circuitBreaker;
    private WakeHook wakeHook;
    private Thread cycleThread;

    public DefaultSlotManager(@NonNull Config config, CircuitBreaker circuitBreaker) {
        this.config = config;

        int chunkSize = Integer.highestOneBit((config.bufferChunkSize - 1) << 1);
        chunkSize = Math.clamp(chunkSize, 64, 16_392);

        this.buffer = new SpscUnboundedUnpaddedArrayQueue<>(chunkSize);
        this.chunkSize = chunkSize;
        this.bufferWrapper = new DrainBuffer(buffer);

        this.currentRate = config.initialConcurrency;
        this.currentConcurrency = Math.max(1, config.initialConcurrency);
        this.effectiveConcurrencyLimit = config.initialConcurrency;

        this.executionLatency = new FlowRecorder();

        this.circuitBreaker =
                circuitBreaker == null ? CircuitBreaker.ofDefaults("DefaultSlotManager")
                        : circuitBreaker;
        configureCircuitBreaker();

        if (config.cloneConfig == null) {
            this.cpuId = -1;
            this.pinnedExecutor = null;
            this.logger = LoggerFactory.getLogger(DefaultSlotManager.class);
        } else {
            int[] cpus = getCpus(config.cloneConfig.effectiveCpus());
            this.cpuId = cpus[0];
            this.pinnedExecutor = PinnedThreadExecutor.getOrSetIfAbsent(cpus[0],
                    config.cloneConfig.shardName() + "-DefaultSlotManager-"
                            + config.cloneConfig.coreId(),
                    Thread.MAX_PRIORITY, false);
            this.logger = LoggerFactory.getLogger(
                    config.cloneConfig.shardName() + "-DefaultSlotManager");
        }

        this.failureBroadcast.asFlux().doOnNext(_ -> recentErrors.incrementAndGet())
                .window(Duration.ofSeconds(1)).flatMap(_ -> Mono.delay(Duration.ofSeconds(2)))
                .subscribe(_ -> recentErrors.set(0));

        this.metrics = new Metrics(config.meterRegistry, config, inFlight, () -> avgLatency,
                () -> currentConcurrency,
                () -> currentRate);

        final int mask = Integer.highestOneBit((config.latencyRecordInterval - 1) << 1) - 1;
        outputFlux = new DirectOutputFlux(buffer, frame -> {
            if ((executions++ & mask) == 0) {
                frame.setStartNs(System.nanoTime());
            } else {
                frame.setStartNs(0);
            }
            frame.setCompletionSink(completeSink);
        });
        this.completeSink.asFlux().subscribe(this::recordCompletion);

        this.errorSink.asFlux().subscribe(
                failure -> circuitBreaker.onError(failure.duration(), TimeUnit.NANOSECONDS,
                        failure.exception()));

        this.shutdownHook = new Thread(this::close);
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    protected void configureCircuitBreaker() {
        String cbName = this.circuitBreaker.getName();
        CircuitBreakerConfig cbConfig = this.circuitBreaker.getCircuitBreakerConfig();
        Predicate<Throwable> existingPredicate = cbConfig.getIgnoreExceptionPredicate();

        var updated = CircuitBreakerConfig.from(cbConfig).ignoreException(
                throwable -> throwable instanceof AbstractFrame || existingPredicate.test(
                        throwable)).build();

        if (config.cbRegistry == null) {
            this.circuitBreaker = CircuitBreaker.of(cbName, updated);
        } else {
            this.circuitBreaker = config.cbRegistry.circuitBreaker(cbName, updated);
        }

        this.circuitBreaker.getEventPublisher().onError(this.failureBroadcast::tryEmitNext);
    }

    protected void recordCompletion(AbstractFrame frame) {
        if (!frame.isCancelledExecution() && frame.getStartNs() > 0) {
            long now = System.nanoTime();
            executionLatency.record(now, now - frame.getStartNs());
            updateMetrics(now - frame.getStartNs());
            avgLatency = (long) (executionLatency.getAverageUnits()
                    + executionLatency.getUnitVariation());
        }
        inFlight.decrementAndGet();
        frame.doFinally();
    }

    protected void updateMetrics(long latencyNs) {
        double queueEstimate = executionLatency.getVegasQueueEstimate(latencyNs,
                currentConcurrency);

        updateEffectiveConcurrencyLimit();
        updateConcurrency(queueEstimate);
    }

    protected void updateEffectiveConcurrencyLimit() {
        CpuSnapshot cpuSnapshot = coreSnapshot.cpuSnapshots()[cpuId];
        long ideal = executionLatency.getThroughputNs();

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

    protected void updateConcurrency(double queueEstimate) {
        if (drainMode) {
            this.currentConcurrency = effectiveConcurrencyLimit;
            return;
        }

        long current = currentConcurrency;

        // Vegas thresholds
        long logOfCurrent = calculateLog2(current);
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

        vegasFactor = Math.max(-1.0, Math.min(1.0, vegasFactor));

        double cvRate = executionLatency.getRateCV();
        double cvLatency = executionLatency.getUnitCV();
        double variability = Math.min(1.0, (cvRate + cvLatency) * 0.5);

        long ideal = (long) (executionLatency.getThroughputNs() * (1.0 + variability));

        double littlesFactor = 0.0;
        if (ideal > 0) {
            littlesFactor = (ideal - current) / (double) ideal;
            littlesFactor = Math.max(-1.0, Math.min(1.0, littlesFactor));
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

        this.currentConcurrency = Math.clamp(next, 2, effectiveConcurrencyLimit);
    }

    private long calculateLog2(long current) {
        long log2 = 63 - Long.numberOfLeadingZeros(Math.max(current, 1));

        return Math.max(3, log2);
    }

    private int[] getCpus(BitSet effective) {
        int[] cpus = new int[effective.cardinality()];
        int idx = 0;
        for (int c = effective.nextSetBit(0); c >= 0; c = effective.nextSetBit(c + 1)) {
            cpus[idx++] = c;
        }
        return cpus;
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
            completeSink.tryEmitComplete();
            metrics.close();
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
    public Publisher<AbstractFrame> process(Publisher<AbstractFrame> flux) {
        ingest(flux);
        return output();
    }

    @Override
    public void ingest(Publisher<AbstractFrame> frameFlux) {
        if (frameFlux instanceof IngestSequencer sequencer && ingest == null) {
            ingest = sequencer;
            wakeHook = new WakeHook(cycleThread);
            sequencer.setWakeHook(wakeHook);
            LockSupport.unpark(cycleThread);
        }
    }

    @Override
    public Publisher<AbstractFrame> output() {
        return outputFlux;
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
                EmitResult result;
                while (!(result = errorSink.tryEmitNext(failure)).isSuccess()) {
                    if (result == EmitResult.FAIL_CANCELLED || result == EmitResult.FAIL_TERMINATED
                            || result == EmitResult.FAIL_ZERO_SUBSCRIBER) {
                        logger.error("CRITICAL: No error sink available to report failure.",
                                failure.exception());
                        break;
                    }
                    Thread.onSpinWait();
                }
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

    public void start() {
        if (running.compareAndSet(false, true)) {
            CloneConfig cloneConfig = config.cloneConfig;
            if (cloneConfig != null) {

                pinnedExecutor.execute(() -> {
                    this.cycleThread = Thread.currentThread();

                    logger.info("Pinned to Core {} CPU {}", cloneConfig.coreId(), cpuId);
                    cycle();
                });
            } else {
                cycle();
            }
        }
    }

    private void cycle() {
        try {
            final long lowWaterMark = chunkSize >> 2;

            FlowRecorder fillRecorder = null;
            FlowRecorder fillBytesRecorder = null;
            FlowRecorder drainRecorder = null;
            final FlowRecorder arrivalLatency = bufferWrapper.arrivalLatencyRecorder;
            final FlowRecorder idleRecorder = new FlowRecorder();

            int requests = 0;
            long demandWaitNs = 0;
            long lastRequestNs = 0;

            long maxParkNs = config.idleCyclePolicy.maxParkTime.toNanos();
            while (running.get() && !Thread.currentThread().isInterrupted()) {

                long currentConcurrency = this.currentConcurrency;
                int currInFlight = inFlight.get();
                long quota = Math.max(0, currentConcurrency - currInFlight);

                int processed = 0;
                if (quota > 0 && bufferCount > 0 && acquirePermission()) {
                    processed = outputFlux.drain(quota);
                    if (processed > 0) {
                        bufferCount -= processed;
                        this.inFlight.addAndGet(processed);

                        idleRecorder.record(System.nanoTime(), 0);
                        requests >>>= 1;

                        if ((processed & 127) == 0) {
                            Thread.onSpinWait();
                        }
                        continue;
                    } else if (upstreamCount > 0) {
                        idleSpin(idleRecorder, requests, maxParkNs);
                    }
                }

                if (ingest == null) {
                    idleSpin(idleRecorder, requests, maxParkNs);
                    continue;
                }

                if (drainRecorder == null) {
                    fillRecorder = ingest.getFillRecorder();
                    fillBytesRecorder = ingest.getFillBytesRecorder();
                    drainRecorder = ingest.getDrainRecorder();
                }

                long ingestCount = ingest.getCount();

                if (bufferCount > lowWaterMark && ingestCount == 0) {
                    idleSpin(idleRecorder, requests, maxParkNs);
                    continue;
                }


                long newUpCount = ingest.getUpstreamCount();
                if (upstreamCount != newUpCount) {
                    idleRecorder.reset();
                    requests = 0;
                    upstreamCount = newUpCount;
                } else if (upstreamCount > 0 && processed == 0 && ingestCount == 0
                        && lastRequestNs - fillRecorder.getLastRecordingTime() > 10 * maxParkNs) {
                    idleSpin(idleRecorder, 15, maxParkNs);
                    continue;
                }

                double drainRate = drainRecorder.getAverageRate();
                double drainRateVar = drainRecorder.getRateVariation();
                double arrivalLatencyNs = arrivalLatency.getAverageUnits();
                double arrivalLatencyVar = arrivalLatency.getUnitVariation();
                double avgFrameSize = fillBytesRecorder.getAverageUnits()
                        + fillBytesRecorder.getUnitVariation();

                long demand = DemandOptimizer.getDemand(drainRate, arrivalLatencyNs,
                        drainRateVar, arrivalLatencyVar, bufferCount + ingestCount,
                        (long) avgFrameSize, ingest.getMaxQueuedBytes());

                long maxFill = chunkSize - bufferCount;
                if (demand < maxFill) {
                    demand += maxFill - bufferCount;
                }

                long nowNs = System.nanoTime();

                if (nowNs < demandWaitNs) {
                    demand = 0;
                } else {
                    boolean warmedUp = fillRecorder.getRollingSum() > chunkSize
                            && fillRecorder.getAverageInterval() > 0
                            && fillRecorder.getAverageUnits() > 0;

                    if (warmedUp) {
                        double fillRate = fillRecorder.getAverageRate();
                        double execLatency = executionLatency.getAverageUnits();

                        double execRate = 1.0 / Math.max(execLatency, 1.0);

                        if (fillRate < execRate * 0.5) {
                            demandWaitNs = nowNs + (long) fillRecorder.getAverageInterval();
                        } else {
                            double fillInterval = fillRecorder.getAverageInterval()
                                    + fillRecorder.getIntervalVariation();
                            fillInterval = Math.max(fillInterval, 1_000);

                            double avgFill = fillRecorder.getAverageUnits()
                                    + fillRecorder.getUnitVariation();
                            avgFill = Math.max(avgFill, 64);

                            double intervalCount = maxFill / avgFill;

                            long maxWaitNs = (long) (execLatency * chunkSize / 2);
                            maxWaitNs = Math.min(maxWaitNs, maxParkNs);

                            long fillWait = (long) (intervalCount * fillInterval);
                            demandWaitNs = nowNs + Math.min(maxWaitNs, fillWait);
                        }
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

    protected void idleSpin(FlowRecorder idleRecorder, int requests, long maxParkNs) {
        long now = System.nanoTime();
        idleRecorder.record(now, 1);

        double idleRatio = idleRecorder.getRollingAverage(now);
        if (idleRatio <= config.idleCyclePolicy.spinThreshold) {
            Thread.onSpinWait();
        } else if (idleRatio <= config.idleCyclePolicy.yieldThreshold) {
            Thread.yield();
        } else if (requests >= 15) {
            while (requests-- > 10 && ingest.getCount() == 0) {
                if (ingest != null) {
                    bufferWrapper.reset();
                    ingest.pull(bufferWrapper, chunkSize - bufferCount);
                    bufferCount += (int) bufferWrapper.drainCount;
                    if (bufferWrapper.drainCount > 0) {
                        break;
                    }
                }
                long newUpCount = ingest.getUpstreamCount();
                if (upstreamCount != newUpCount) {
                    idleRecorder.reset();
                    upstreamCount = newUpCount;
                    break;
                }

                if (wakeHook != null) {
                    wakeHook.parked = true;
                }
                LockSupport.parkNanos(maxParkNs);
                if (wakeHook != null) {
                    wakeHook.parked = false;
                }
            }
        }
    }

    protected boolean acquirePermission() {
        return drainMode || circuitBreaker.tryAcquirePermission();
    }

    @Override
    public DefaultSlotManager clone(CloneConfig cloneConfig) {
        String cbName = this.circuitBreaker.getName();
        CircuitBreakerConfig cbConfig = this.circuitBreaker.getCircuitBreakerConfig();

        CircuitBreaker copyCB = null;
        if (cloneConfig != null) {
            if (config.cbRegistry == null) {
                copyCB = CircuitBreaker.of(
                        cloneConfig.shardName() + "-" + cbName + "-core-" + cloneConfig.coreId(),
                        cbConfig);
            } else {
                copyCB = config.cbRegistry.circuitBreaker(
                        cloneConfig.shardName() + "-" + cbName + "-core-" + cloneConfig.coreId(),
                        cbConfig);
            }
        }

        return new DefaultSlotManager(config.clone(cloneConfig), copyCB);
    }

    @Override
    public double getPressure() {
        double cbPressure = switch (circuitBreaker.getState()) {
            case OPEN, FORCED_OPEN -> 1.0;
            case HALF_OPEN -> 0.85;
            default -> 0.0;
        };
        if (cbPressure >= 1.0) {
            return 1.0;
        }

        long alpha = Math.max(3, 3 * calculateLog2(currentConcurrency));
        long beta = Math.max(6, 6 * alpha);

        double queueEstimate = executionLatency.getVegasQueueEstimate(
                executionLatency.getAverageUnits() + executionLatency.getUnitVariation(),
                currentConcurrency);

        double vegasPressure = queueEstimate / beta;

        double errorPenalty = Math.min(0.4, recentErrors.get() * 0.05);
        double hardwarePressure =
                coreSnapshot != null ? coreSnapshot.cpuSnapshots()[cpuId].pressure() : 0.0;

        double base = Math.max(vegasPressure, hardwarePressure);
        return Math.clamp(Math.max(base, cbPressure) + errorPenalty, 0.0, 1.0);
    }

    public Flux<Object> addFailureListener() {
        return this.failureBroadcast.asFlux();
    }

    @Override
    public boolean isStarted() {
        return running.get();
    }

    @Override
    public boolean isDrained() {
        return inFlight.get() == 0 && buffer.isEmpty();
    }

    @Override
    public void update(CoreSnapshot snapshot) {
        this.coreSnapshot = snapshot;
    }

    @Override
    public void setDrainMode(boolean value) {
        this.drainMode = value;
    }

    public static final class Metrics implements AutoCloseable {

        public final MeterRegistry registry;
        private final List<Meter> meters = new ArrayList<>();

        public Metrics(MeterRegistry registry, Config config, AtomicInteger inFlight,
                Supplier<Long> latency,
                Supplier<Long> currentConcurrency, Supplier<Long> currentRate) {
            this.registry = registry;

            if (registry != null && config.cloneConfig != null) {
                String coreId = String.valueOf(config.cloneConfig.coreId());

                meters.add(Gauge.builder(config.metricPrefix + ".inFlight.latency", latency)
                        .description("Average time of completion for in-flight work in nanos.")
                        .tag("core", coreId)
                        .baseUnit("nanoseconds").register(registry));

                meters.add(Gauge.builder(config.metricPrefix + ".dispatch.concurrency.current",
                                currentConcurrency).description("Current adaptive concurrency limit")
                        .tag("core", coreId)
                        .register(registry));

                meters.add(Gauge.builder(config.metricPrefix + ".dispatch.inflight.count", inFlight,
                                AtomicInteger::get).description("Number of frames being executed")
                        .tag("core", coreId)
                        .register(registry));

                meters.add(Gauge.builder(config.metricPrefix + ".dispatch.throughput", currentRate)
                        .description("Current dispatch rate (dispatch/sec)")
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

    public record Config(CloneConfig cloneConfig, int initialConcurrency, int bufferChunkSize,
                         int bufferChunkCount, int latencyRecordInterval,
                         IdleCyclePolicy idleCyclePolicy,
                         CircuitBreakerRegistry cbRegistry,
                         MeterRegistry meterRegistry, String metricPrefix) implements
            CloneableObject {

        public static Config balancedDefault(
                CircuitBreakerRegistry cbRegistry,
                MeterRegistry meterRegistry, String metricPrefix) {
            return new Config(null, 5000, 4_096, 2, 64, IdleCyclePolicy.DEFAULT,
                    cbRegistry, meterRegistry, metricPrefix);
        }

        public static Config lowLatencyDefault(CircuitBreakerRegistry cbRegistry,
                MeterRegistry meterRegistry, String metricPrefix) {
            return new Config(null, 5000, 4_096, 2, 128, IdleCyclePolicy.LOW_LATENCY, cbRegistry,
                    meterRegistry, metricPrefix);
        }

        @Override
        public Config clone(CloneConfig cloneConfig) {
            MeterRegistry meterRegistry = null;
            if (cloneConfig != null) {
                meterRegistry = cloneConfig.meterRegistry();
            }
            return new Config(cloneConfig, initialConcurrency, bufferChunkSize, bufferChunkCount,
                    latencyRecordInterval,
                    idleCyclePolicy,
                    cbRegistry,
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
