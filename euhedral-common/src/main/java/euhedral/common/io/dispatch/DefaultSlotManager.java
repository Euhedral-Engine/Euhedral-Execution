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
    protected final FlowRecorder executionLatency;
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

    protected volatile long currentRate;
    protected volatile long currentConcurrency;
    protected volatile long effectiveConcurrencyLimit;

    protected volatile boolean parked = false;
    protected volatile boolean drainMode = false;
    protected volatile CoreSnapshot coreSnapshot = null;

    protected volatile IngestSequencer ingest = null;

    private long executions = 0;

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

        this.failureBroadcast.asFlux().doOnNext(err -> recentErrors.incrementAndGet())
                .window(Duration.ofSeconds(1)).flatMap(_ -> Mono.delay(Duration.ofSeconds(2)))
                .subscribe(v -> recentErrors.set(0));

        final long[] avgLatency = new long[]{0};
        this.metrics = new Metrics(config.meterRegistry, config, inFlight, () -> avgLatency[0],
                () -> currentConcurrency,
                () -> currentRate);

        outputFlux = new DirectOutputFlux(buffer, frame -> {
            if((executions++ & 31) == 0) {
                frame.setStartNs(System.nanoTime());
            }
            frame.setCompletionSink(completeSink);
        });
        this.completeSink.asFlux().subscribe((frame) -> {
            if (!frame.isCancelledExecution() && frame.getStartNs() > 0) {
                long now = System.nanoTime();
                executionLatency.record(now, now - frame.getStartNs());
                updateMetrics(now - frame.getStartNs());
                avgLatency[0] = executionLatency.getAverageUnits();
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
        executionLatency.record(latencyNs);

        double queueEstimate = executionLatency.getVegasQueueEstimate(latencyNs,
                currentConcurrency);

        updateEffectiveConcurrencyLimit();
        updateConcurrency(queueEstimate);
    }

    protected void updateConcurrency(double queueEstimate) {
        if (drainMode) {
            this.currentConcurrency = effectiveConcurrencyLimit;
            return;
        }

        currentRate = executionLatency.getThroughputNs() * 1_000_000_000L;

        long current = currentConcurrency;

        // Vegas Dynamic Thresholds
        long logOfCurrent = calculateLog2(current);
        long alpha = Math.max(3, 3 * logOfCurrent);
        long beta = Math.max(6, 6 * logOfCurrent);

        long next;
        if (queueEstimate > beta) {
            long decrease = ((current * 9830) >> 16); // ~15%
            next = current - Math.max(1, decrease);
        } else if (queueEstimate < (alpha >> 1)) {
            long increase = ((current * 6553) >> 16); // ~10%
            next = current + Math.max(2, increase);
        } else if (queueEstimate < alpha) {
            next = current + 1;
        } else { // Little's Law
            long ideal = executionLatency.getThroughputNs();
            long diff = ideal - current;
            int step = (int) ((diff * 19661) >> 16); // ~0.3

            if (step == 0 && diff != 0) {
                step = (diff > 0) ? 1 : -1;
            }

            next = current + step;
        }

        this.currentConcurrency = Math.clamp(next, 2, effectiveConcurrencyLimit);
    }

    private long calculateLog2(long current) {
        long log2 = 63 - Long.numberOfLeadingZeros(Math.max(current, 1));

        return Math.max(3, log2);
    }

    protected void updateEffectiveConcurrencyLimit() {
        CpuSnapshot cpuSnapshot = coreSnapshot.cpuSnapshots()[cpuId];
        long throughput = executionLatency.getThroughputNs();
        long limit = throughput - (long)(throughput * (1.0 - cpuSnapshot.pressure()));
        this.effectiveConcurrencyLimit = Math.max(limit, config.initialConcurrency);
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
            int chunkSize = 4096;
            long minDemand = chunkSize << 3;

            FlowRecorder idleRecorder = new FlowRecorder();
            FlowRecorder arrivalLatency = bufferWrapper.arrivalLatencyRecorder;
            FlowRecorder drainBatchRecorder = null;

            int parks = 0;
            int bufferCount = 0;
            while (running.get() && !Thread.currentThread().isInterrupted()) {

                long currentConcurrency = this.currentConcurrency;
                int currInFlight = inFlight.get();
                long quota = Math.max(0, currentConcurrency - currInFlight);

                int processed = 0;
                if (quota > 0 && bufferCount > 0 && acquirePermission()) {
                    processed = outputFlux.drain(quota);
                    bufferCount -= processed;
                }
                if (processed > 0) {
                    this.inFlight.addAndGet(processed);
                }

                if (ingest != null) {

                    if (drainBatchRecorder == null) {
                        drainBatchRecorder = ingest.getBatchRecorder();
                    }
                    int lowWaterMark = chunkSize >> 2;

                    if (bufferCount <= lowWaterMark) {
                        long ingestCount = ingest.getCount();

                        long arrivalThroughput = arrivalLatency.getThroughputNs();
                        long executionThroughput = executionLatency.getThroughputNs();
                        long targetThroughput = Math.max(arrivalThroughput, executionThroughput);

                        int maxFill = chunkSize - bufferCount;

                        long demand = targetThroughput - (ingestCount + bufferCount);
                        demand = Math.max(demand, minDemand);

                        int drained = ingest.drain(bufferWrapper, maxFill, demand);
                        bufferCount += drained;
                    }
                }

                if (processed > 0) {
                    parks = 0;
                    idleRecorder.record(System.nanoTime(), 0);
                    if ((processed & 128) == 0) {
                        Thread.onSpinWait();
                    }
                } else {
                    long now = System.nanoTime();
                    idleRecorder.record(now, 1);

                    double idleRatio = idleRecorder.getRollingAveragePerRecording(now);
                    if (idleRatio < 0.25) {
                        Thread.onSpinWait();
                    } else if (idleRatio < 0.6) {
                        Thread.yield();
                    } else {
                        parks = Math.min(parks + 1, 20);

                        if (wakeHook != null) {
                            wakeHook.parked = true;
                        }

                        long parkNs = parks * 1_000L;
                        LockSupport.parkNanos(parkNs);

                        if (wakeHook != null) {
                            wakeHook.parked = false;
                        }
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

        long queueEstimate = executionLatency.getVegasQueueEstimate(
                executionLatency.getAverageUnits(),
                currentConcurrency);

        double vegasPressure = (double) queueEstimate / beta;

        double errorPenalty = Math.min(0.4, recentErrors.get() * 0.05);
        double hardwarePressure = coreSnapshot != null ? coreSnapshot.cpuSnapshots()[cpuId].pressure() : 0.0;

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

    public record Config(CloneConfig cloneConfig, int initialRatePerSecond, int initialConcurrency,
                         int concurrencyMultiplier, int maxBuffer,
                         CircuitBreakerRegistry cbRegistry,
                         MeterRegistry meterRegistry, String metricPrefix) implements
            CloneableObject {

        public static final int VIRTUAL_THREAD_MULT = 20_000;

        public static Config elasticVirtualDefault(
                CircuitBreakerRegistry cbRegistry, MeterRegistry meterRegistry,
                String metricPrefix) {
            return new Config(null, 10_000, VIRTUAL_THREAD_MULT, VIRTUAL_THREAD_MULT, 16_384,
                    cbRegistry, meterRegistry, metricPrefix);
        }

        public static Config mixedWorkDefault(
                CircuitBreakerRegistry cbRegistry,
                MeterRegistry meterRegistry, String metricPrefix) {
            return new Config(null, 2000, 20, 256, 1024,
                    cbRegistry, meterRegistry, metricPrefix);
        }

        @Override
        public Config clone(CloneConfig cloneConfig) {
            MeterRegistry meterRegistry = null;
            if (cloneConfig != null) {
                meterRegistry = cloneConfig.meterRegistry();
            }
            return new Config(cloneConfig, initialRatePerSecond, initialConcurrency,
                    concurrencyMultiplier, maxBuffer, cbRegistry,
                    meterRegistry, metricPrefix);
        }

        @Override
        public void close() {
        }
    }
}
