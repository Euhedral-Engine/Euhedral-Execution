package euhedral.common.io.dispatch;

import euhedral.common.io.dispatch.control_plane.CloneConfig;
import euhedral.common.io.dispatch.flow_control.DirectOutputFlux;
import euhedral.common.io.dispatch.flow_control.IngestSequencer;
import euhedral.common.io.dispatch.flow_control.IngestSequencer.WakeHook;
import euhedral.common.io.dispatch.frames.AbstractFrame;
import euhedral.common.io.dispatch.frames.QueueFrame.DrainBuffer;
import euhedral.common.io.dispatch.interfaces.CloneableObject;
import euhedral.common.io.dispatch.interfaces.SlotManager;
import euhedral.common.io.dispatch.utils.FlowRecorder;
import euhedral.common.io.dispatch.utils.PinnedThreadExecutor;
import euhedral.common.io.dispatch.utils.SystemUtilization.CoreSnapshot;
import euhedral.common.io.dispatch.utils.SystemUtilization.CpuSnapshot;
import euhedral.common.io.dispatch.utils.SystemUtilization.HardwareUtilization;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Predicate;
import java.util.function.Supplier;
import lombok.AccessLevel;
import lombok.Getter;
import org.jctools.queues.MpscUnboundedXaddArrayQueue;
import org.jctools.queues.SpscUnboundedArrayQueue;
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
    protected final FlowRecorder latencyRecorder;
    protected final AtomicBoolean running = new AtomicBoolean(false);
    protected final AtomicLong recentErrors = new AtomicLong(0);
    protected final Sinks.Many<Object> failureBroadcast = Sinks.many().multicast()
            .onBackpressureBuffer();
    protected final AtomicInteger inFlight = new AtomicInteger(0);
    protected final SpscUnboundedArrayQueue<AbstractFrame> buffer;
    protected final DrainBuffer bufferWrapper;
    protected final Sinks.Many<AbstractFrame> completeSink = Sinks.many().unicast()
            .onBackpressureBuffer(new MpscUnboundedXaddArrayQueue<>(2048, 4));
    private final Thread shutdownHook;
    private final Logger logger;
    private final int cpuId;
    @Getter
    private final PinnedThreadExecutor pinnedExecutor;
    private final Sinks.Many<Failure> errorSink = Sinks.many().unicast()
            .onBackpressureBuffer(new MpscUnboundedXaddArrayQueue<>(1024));

    private final DirectOutputFlux outputFlux;
    private final RateLimiter rateLimiter;
    protected AtomicReference<HardwareUtilization> hardwareUtilization;

    protected volatile int baseCap;
    protected volatile int currentRate;
    protected volatile int currentConcurrency;
    protected volatile int effectiveConcurrencyLimit;

    protected volatile boolean parked = false;
    protected volatile boolean drainMode = false;
    protected volatile CoreSnapshot coreSnapshot = null;

    protected volatile IngestSequencer ingest = null;

    private CircuitBreaker circuitBreaker;
    private WakeHook wakeHook;
    private Thread cycleThread;

    public DefaultSlotManager(@NonNull Config config, CircuitBreaker circuitBreaker) {
        this.config = config;

        this.buffer = new SpscUnboundedArrayQueue<>(4096);
        this.bufferWrapper = new DrainBuffer(buffer);

        this.currentRate = config.initialRatePerSecond;
        this.currentConcurrency = Math.max(1, config.initialConcurrency);
        this.effectiveConcurrencyLimit = config.initialConcurrency;

        this.latencyRecorder = new FlowRecorder();

        this.rateLimiter = configureRateLimiter(config.initialRatePerSecond);
        this.rateLimiter.getEventPublisher().onFailure(this.failureBroadcast::tryEmitNext);

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

        this.failureBroadcast.asFlux().doOnNext(err -> recentErrors.incrementAndGet())
                .window(Duration.ofSeconds(1)).flatMap(_ -> Mono.delay(Duration.ofSeconds(2)))
                .subscribe(v -> recentErrors.set(0));

        final long[] avgLatency = new long[]{0};
        this.metrics = new Metrics(config.meterRegistry, config, inFlight, () -> avgLatency[0],
                () -> currentConcurrency,
                () -> currentRate);

        outputFlux = new DirectOutputFlux(buffer, frame -> frame.setCompletionSink(completeSink));
        this.completeSink.asFlux().subscribe((frame) -> {
            if (!frame.isCancelledExecution() && frame.getStartNs() > 0) {
                long now = System.nanoTime();
                latencyRecorder.record(now, now - frame.getStartNs());
                if ((latencyRecorder.getEffectiveMeasurementWindowCount(now) & 31) == 0) {
                    updateMetrics(now - frame.getStartNs());
                }
                avgLatency[0] = latencyRecorder.getAverageUnits();
            }
            inFlight.decrementAndGet();
            frame.doFinally();
        });

        this.errorSink.asFlux().subscribe(
                failure -> circuitBreaker.onError(failure.duration(), TimeUnit.NANOSECONDS,
                        failure.exception()));

        this.shutdownHook = new Thread(this::close);
        Runtime.getRuntime().addShutdownHook(this.shutdownHook);
    }

    protected RateLimiter configureRateLimiter(int ratePerSecond) {
        RateLimiterConfig config = RateLimiterConfig.custom().limitForPeriod(ratePerSecond)
                .limitRefreshPeriod(Duration.ofSeconds(1)).timeoutDuration(Duration.ZERO).build();

        return RateLimiter.of("DefaultSlotManager", config);
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

    private int[] getCpus(BitSet effective) {
        int[] cpus = new int[effective.cardinality()];
        int idx = 0;
        for (int c = effective.nextSetBit(0); c >= 0; c = effective.nextSetBit(c + 1)) {
            cpus[idx++] = c;
        }
        return cpus;
    }

    protected void updateMetrics(long latencyNs) {
        latencyRecorder.record(latencyNs);

        double queueEstimate = latencyRecorder.getVegasQueueEstimate(latencyNs, currentConcurrency);

        updateEffectiveConcurrencyLimit();
        updateConcurrency(queueEstimate);
    }

    protected void updateConcurrency(double queueEstimate) {
        if (drainMode) {
            this.currentConcurrency = effectiveConcurrencyLimit;
            return;
        }

        long throughputPerSec = latencyRecorder.getThroughputNs() * 1_000_000_000L;
        currentRate = (int) throughputPerSec;

        int current = currentConcurrency;

        // Vegas Dynamic Thresholds
        int logOfCurrent = calculateLog2(current);
        int alpha = Math.max(3, 3 * logOfCurrent);
        int beta = Math.max(6, 6 * logOfCurrent);

        int next;
        if (queueEstimate > beta) {
            int decrease = ((current * 9830) >> 16); // 9830/65536 ~= 15%
            next = current - Math.max(1, decrease);
        } else if (queueEstimate < (alpha >> 1)) {
            int increase = ((current * 6553) >> 16); // 6553/65536 ~= 10%
            next = current + Math.max(2, increase);
        } else if (queueEstimate < alpha) {
            next = current + 1;
        } else { // Little's Law
            long ideal = latencyRecorder.getThroughputNs();
            long diff = ideal - current;
            // Move 30% of the way to the ideal anchor
            int step = (int) ((diff * 19661) >> 16); // 19661/65536 ≈ 0.3

            if (step == 0 && diff != 0) {
                step = (diff > 0) ? 1 : -1;
            }

            next = current + step;
        }

        this.currentConcurrency = Math.clamp(next, 2, effectiveConcurrencyLimit);
        updateRateLimiter(throughputPerSec);
    }

    private void updateRateLimiter(long throughputPerSec) {
        boolean underUtilized;
        if (config.cloneConfig == null) {
            underUtilized = hardwareUtilization.get().pressure() < 0.90;
        } else {
            underUtilized = coreSnapshot.cpuSnapshots()[cpuId].pressure() < 0.90;
        }

        if (underUtilized) {
            int targetRps = Math.max(10, (int) throughputPerSec);
            int currentRps = rateLimiter.getRateLimiterConfig().getLimitForPeriod();

            // Use a 5% dead-band to prevent jitter
            if (Math.abs(currentRps - targetRps) > ((currentRps * 3276) >> 16)) {
                rateLimiter.changeLimitForPeriod(targetRps);
            }
        }
    }

    private int calculateLog2(int current) {
        int log2 = 31 - Integer.numberOfLeadingZeros(Math.max(current, 1));

        return Math.max(3, log2);
    }

    protected void updateEffectiveConcurrencyLimit() {
        CircuitBreaker.State cbState = circuitBreaker.getState();

        if (!drainMode && (cbState == CircuitBreaker.State.OPEN
                || cbState == CircuitBreaker.State.FORCED_OPEN)) {
            effectiveConcurrencyLimit = 10;
            return;
        }

        CoreSnapshot coreSnapshot = this.coreSnapshot;
        CpuSnapshot snapshot = coreSnapshot.cpuSnapshots()[cpuId];

        double pressure = snapshot.pressure();
        int baseCap = (int) (snapshot.quotaCpus() * config.concurrencyMultiplier);

        final int[] target = new int[1];
        if (pressure > 0.70) {
            double normPressure = (pressure - 0.70) / 0.30;
            target[0] = (int) (baseCap * (1.0 - (0.8 * normPressure)));
        } else {
            target[0] = baseCap;
        }
        target[0] = Math.max(10, target[0]);

        int current = this.effectiveConcurrencyLimit;
        if (drainMode) {
            this.effectiveConcurrencyLimit = (int) (baseCap * 0.8);
        } else if (cbState == CircuitBreaker.State.HALF_OPEN) {
            this.effectiveConcurrencyLimit = Math.min(target[0], 50);
        } else if (target[0] < current) {
            this.effectiveConcurrencyLimit = target[0]; // Fast Drop
        } else {
            // Slow Rise: move 1/8th of the way
            int delta = target[0] - current;
            this.effectiveConcurrencyLimit = current + Math.max(1, delta >> 3);
        }

    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            if(ingest != null) {
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
            public void onSubscribe(Subscription subscription) {
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
            int chunkSize = 4096;

            int parks = 0;
            int bufferCount = 0;
            long idleIterations = 0;
            while (running.get() && !Thread.currentThread().isInterrupted()) {

                int currentConcurrency = this.currentConcurrency;
                int currInFlight = inFlight.get();
                int quota = currentConcurrency - currInFlight;

                int processed = 0;
                if (acquirePermission(quota)) {
                    processed = bufferCount > 0 && quota > 0 ? outputFlux.drain(quota) : 0;
                    bufferCount -= processed;
                }
                if (processed > 0) {
                    this.inFlight.addAndGet(processed);
                }

                if (ingest != null) {
                    int lowWaterMark = Math.min(currentConcurrency >> 2, chunkSize >> 2);

                    if (bufferCount <= lowWaterMark) {
                        bufferCount += ingest.drain(bufferWrapper, chunkSize - bufferCount);
                    }
                }

                if (processed > 0) {
                    parks = 0;
                    idleIterations = 0;
                    if ((processed & 128) == 0) {
                        Thread.onSpinWait();
                    }
                } else {
                    idleIterations++;
                    if (idleIterations >= config.idleCyclePolicy.idleParkThreshold) {
                        parks = Math.min(++parks, 20);
                        if (wakeHook != null) {
                            wakeHook.parked = true;
                        }
                        LockSupport.parkNanos(parks * 1_000L);
                        idleIterations = config.idleCyclePolicy.idleParkThreshold;
                        if (wakeHook != null) {
                            wakeHook.parked = false;
                        }
                    } else if (idleIterations >= config.idleCyclePolicy.idleYieldThreshold) {
                        Thread.yield();
                    } else if (idleIterations >= config.idleCyclePolicy.idleSpinThreshold) {
                        Thread.onSpinWait();
                    }
                }
            }
        } catch (Throwable e) {
            logger.error("Error", e);
        } finally {
            running.set(false);
            parked = false;
        }
    }

    protected boolean acquirePermission(int quota) {
        return drainMode || (circuitBreaker.tryAcquirePermission());
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

        double hardwarePressure = 0.0;
        if (config.cloneConfig != null) {
            CoreSnapshot snapshot = coreSnapshot;
            if (snapshot != null) {
                hardwarePressure = snapshot.cpuSnapshots()[cpuId].pressure();
            }
        }

        int alpha = Math.max(3, 3 * calculateLog2(currentConcurrency));
        int beta = Math.max(6, 6 * alpha);

        // Get the queue estimate from your new FlowRecorder (latency version)
        // We use long-based math for the estimate, then cast to double for the final mix.
        long queueEstimate = latencyRecorder.getVegasQueueEstimate(
                latencyRecorder.getAverageUnits(), // Passing "actual" units
                currentConcurrency);

        double vegasPressure = (double) queueEstimate / beta;

        double errorPenalty = Math.min(0.4, recentErrors.get() * 0.05);

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
                Supplier<Integer> currentConcurrency, Supplier<Integer> currentRate) {
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

    public record Config(CloneConfig cloneConfig, int initialRatePerSecond, int initialConcurrency,
                         int concurrencyMultiplier, int maxBuffer, IdleCyclePolicy idleCyclePolicy,
                         CircuitBreakerRegistry cbRegistry,
                         MeterRegistry meterRegistry, String metricPrefix) implements
            CloneableObject {

        public static final int VIRTUAL_THREAD_MULT = 20_000;

        public static Config powerSavingDefault(
                CircuitBreakerRegistry cbRegistry, MeterRegistry meterRegistry,
                String metricPrefix) {
            return new Config(null, 1000, 2, 10, 256, IdleCyclePolicy.POWER_SAVING,
                    cbRegistry, meterRegistry, metricPrefix);
        }

        public static Config elasticVirtualDefault(
                CircuitBreakerRegistry cbRegistry, MeterRegistry meterRegistry,
                String metricPrefix) {
            return new Config(null, 10_000, VIRTUAL_THREAD_MULT, VIRTUAL_THREAD_MULT, 16_384,
                    IdleCyclePolicy.DEFAULT, cbRegistry, meterRegistry, metricPrefix);
        }

        public static Config mixedWorkDefault(
                CircuitBreakerRegistry cbRegistry,
                MeterRegistry meterRegistry, String metricPrefix) {
            return new Config(null, 2000, 20, 256, 1024, IdleCyclePolicy.DEFAULT,
                    cbRegistry, meterRegistry, metricPrefix);
        }

        public static Config lowLatencyDefault(
                CircuitBreakerRegistry cbRegistry, MeterRegistry meterRegistry,
                String metricPrefix) {
            return new Config(null, 2000, 1, 2, 64, IdleCyclePolicy.LOW_LATENCY,
                    cbRegistry, meterRegistry, metricPrefix);
        }

        public static Config highThroughputDefault(
                CircuitBreakerRegistry cbRegistry, MeterRegistry meterRegistry,
                String metricPrefix) {
            return new Config(null, 5000, 64, 128, 4096, IdleCyclePolicy.HIGH_THROUGHPUT,
                    cbRegistry, meterRegistry, metricPrefix);
        }

        @Override
        public Config clone(CloneConfig cloneConfig) {
            MeterRegistry meterRegistry = null;
            if (cloneConfig != null) {
                meterRegistry = cloneConfig.meterRegistry();
            }
            return new Config(cloneConfig, initialRatePerSecond, initialConcurrency,
                    concurrencyMultiplier, maxBuffer, idleCyclePolicy, cbRegistry,
                    meterRegistry, metricPrefix);
        }

        @Override
        public void close() {
        }

        public record IdleCyclePolicy(long idleSpinThreshold, long idleYieldThreshold,
                                      long idleParkThreshold) {

            public static final IdleCyclePolicy DEFAULT = new IdleCyclePolicy(1_000, 10_000,
                    100_000);
            public static final IdleCyclePolicy LOW_LATENCY = new IdleCyclePolicy(100_000,
                    1_000_000,
                    1_000_000_000);
            public static final IdleCyclePolicy HIGH_THROUGHPUT = new IdleCyclePolicy(100, 1_000,
                    10_000);
            public static final IdleCyclePolicy POWER_SAVING = new IdleCyclePolicy(0, 0, 0);
        }
    }
}
