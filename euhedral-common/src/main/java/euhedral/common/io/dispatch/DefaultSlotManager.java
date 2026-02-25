package euhedral.common.io.dispatch;

import euhedral.common.io.dispatch.interfaces.SlotManager;
import euhedral.common.io.dispatch.utils.LatencyRecorder;
import euhedral.common.io.dispatch.utils.ResourceMonitor;
import euhedral.common.io.dispatch.utils.ResourceMonitor.HardwareUtilization;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.ratelimiter.operator.RateLimiterOperator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import lombok.AccessLevel;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.core.publisher.Sinks.One;
import reactor.core.scheduler.Scheduler;
import reactor.util.retry.Retry;

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
 * <h2>Admission Control</h2>
 * <p>
 * When concurrency is saturated, callers enter a waiter queue. The queue
 * capacity scales with the effective concurrency to prevent unbounded memory
 * growth. If the queue reaches capacity, behavior is governed by
 * {@link OverloadStrategy}
 * <p>
 * Overload conditions represent admission control decisions and should
 * generally be ignored by the CircuitBreader.
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
    protected final ResourceMonitor resourceMonitor;
    protected final Scheduler scheduler;
    // State
    protected final AtomicInteger inFlight = new AtomicInteger(0);
    protected final AtomicInteger currentConcurrency = new AtomicInteger(0);
    protected final AtomicInteger effectiveConcurrencyLimit = new AtomicInteger(1);
    protected final AtomicInteger currentRate = new AtomicInteger(0);
    // Waiters
    protected final ConcurrentLinkedDeque<One<Void>> waiters = new ConcurrentLinkedDeque<>();
    protected final AtomicInteger currentWaiters = new AtomicInteger(0);
    protected final AtomicInteger effectiveMaxWaiters = new AtomicInteger();
    protected final AtomicLong measurementCount = new AtomicLong();
    protected final LatencyRecorder latencyRecorder;
    protected final AtomicLong recentErrors = new AtomicLong(0);
    protected final Sinks.Many<Object> failureBroadcast = Sinks.many().multicast()
            .onBackpressureBuffer();
    // Resilience
    protected final CircuitBreaker circuitBreaker;
    // Prometheus
    private final MeterRegistry registry;
    private final Timer dispatchTimer;
    private final Timer slotAcquisitionTimer;
    // Metrics
    protected AtomicReference<HardwareUtilization> hardwareUtilization;
    protected RateLimiter rateLimiter;

    public DefaultSlotManager(@NonNull Config config,
            @NonNull ResourceMonitor resourceMonitor,
            @NonNull CircuitBreaker circuitBreaker,
            @NonNull Scheduler scheduler) {
        this(config, resourceMonitor, circuitBreaker, scheduler, null);
    }

    public DefaultSlotManager(@NonNull Config config,
            @NonNull ResourceMonitor resourceMonitor,
            @NonNull CircuitBreaker circuitBreaker,
            @NonNull Scheduler scheduler, MeterRegistry registry) {
        this.config = config;
        this.resourceMonitor = resourceMonitor;
        this.scheduler = scheduler;

        this.currentRate.set(config.initialRatePerSecond);
        this.currentConcurrency.set(config.initialConcurrency);
        this.effectiveConcurrencyLimit.set(config.initialConcurrency);
        this.effectiveMaxWaiters.set(config.maxWaiters);

        this.latencyRecorder = config.latencyRecorder == null ? new LatencyRecorder(scheduler)
                : config.latencyRecorder;

        this.rateLimiter = configureRateLimiter(config.initialRatePerSecond);
        this.rateLimiter.getEventPublisher()
                .onFailure(this.failureBroadcast::tryEmitNext);

        this.circuitBreaker = circuitBreaker;
        this.circuitBreaker.getEventPublisher()
                .onError(this.failureBroadcast::tryEmitNext);

        this.registry = registry;

        this.hardwareUtilization = new AtomicReference<>(resourceMonitor.getUtilization());
        resourceMonitor.addListener().publishOn(scheduler).subscribe(this::updateUtilization);

        this.failureBroadcast.asFlux()
                .doOnNext(err -> recentErrors.incrementAndGet())
                .window(Duration.ofSeconds(1))
                .flatMap(window -> Mono.delay(Duration.ofSeconds(2)))
                .subscribe(v -> recentErrors.set(0));

        if (registry != null) {
            this.dispatchTimer = Timer.builder(config.metricPrefix + ".dispatch.latency")
                    .description("Latency of dispatched work including queue time")
                    .publishPercentileHistogram()
                    .register(registry);
            this.slotAcquisitionTimer = Timer.builder(
                            config.metricPrefix + ".dispatch.slot.acquisition.latency")
                    .description("Latency of acquiring a slot")
                    .publishPercentileHistogram()
                    .register(registry);
            registerPrometheusMetrics();
        } else {
            this.dispatchTimer = null;
            this.slotAcquisitionTimer = null;
        }
    }

    protected RateLimiter configureRateLimiter(int ratePerSecond) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(ratePerSecond)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();

        return RateLimiter.of("defaultSlotManager", config);
    }

    protected <T> UnaryOperator<Publisher<T>> getRateLimiter() {
        return publisher -> Mono.from(publisher)
                .transformDeferred(RateLimiterOperator.of(this.rateLimiter))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(20))
                        .filter(throwable -> throwable instanceof RequestNotPermitted)
                        .jitter(0.5)
                        .scheduler(scheduler));
    }

    @Override
    @NonNull
    public <T> Mono<T> acquireSlot(@NonNull Supplier<Mono<T>> work) {
        return Mono.defer(work)
                .transformDeferred(getRateLimiter())
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .as(readyWork ->
                        acquirePermit().then(Mono.defer(() -> {
                            long startNs = scheduler.now(TimeUnit.NANOSECONDS);
                            Timer.Sample sample = (registry == null) ? null : Timer.start(registry);

                            return readyWork.doFinally(sig -> {
                                if (sample != null) {
                                    sample.stop(dispatchTimer);
                                }
                                releasePermit(); // Guaranteed decrement + drain
                                updateMetrics(scheduler.now(TimeUnit.NANOSECONDS) - startNs);
                            });
                        }))
                );
    }

    protected Mono<Void> acquirePermit() {
        return Mono.defer(() -> {
            Timer.Sample sample = registry == null ? null : Timer.start(registry);

            int limit = currentConcurrency.get();
            int current = inFlight.get();

            if (current < limit && inFlight.compareAndSet(current, current + 1)) {
                if (sample != null) {
                    sample.stop(slotAcquisitionTimer);
                }
                return Mono.empty(); // Success
            }
            int waiterLimit = effectiveMaxWaiters.get();
            if (currentWaiters.incrementAndGet() > waiterLimit) {
                currentWaiters.decrementAndGet();
                return handleOverload(waiterLimit);
            }

            Sinks.One<Void> waiter = Sinks.one();
            waiters.add(waiter);

            return waiter.asMono()
                    .timeout(Duration.ofSeconds(30), scheduler)
                    .then(Mono.defer(this::acquirePermit))
                    .onErrorMap(TimeoutException.class,
                            e -> new RuntimeException("Timed out waiting for concurrency slot"))
                    .doOnSuccess(v -> {
                        if (sample != null) {
                            sample.stop(slotAcquisitionTimer);
                        }
                    })
                    .doFinally(sig -> {
                        if (waiters.remove(waiter)) {
                            currentWaiters.decrementAndGet();
                        }
                    });
        });
    }

    protected void releasePermit() {
        inFlight.decrementAndGet();
        drainWaiters();
    }

    protected void drainWaiters() {
        while (true) {
            Sinks.One<Void> waiter = waiters.pollFirst();
            if (waiter == null) {
                return;
            }

            currentWaiters.decrementAndGet();
            Sinks.EmitResult result = waiter.tryEmitEmpty();

            if (result.isSuccess()) {
                return;
            }

            if (result == EmitResult.FAIL_NON_SERIALIZED) {
                waiters.addFirst(waiter);
                currentWaiters.incrementAndGet();
            }
        }
    }

    protected void updateMetrics(long latencyNs) {
        latencyRecorder.recordLatency(latencyNs);

        double queueEstimate = latencyRecorder.getVegasQueueEstimate(latencyNs,
                latencyRecorder.getTargetLatencyNs(), currentConcurrency.get());
        updateEffectiveConcurrencyLimit();
        updateWaiterLimit();

        if (measurementCount.get() > 15) {
            updateConcurrency(queueEstimate);
        }

        measurementCount.incrementAndGet();
    }

    protected void updateConcurrency(double queueEstimate) {
        double throughput = latencyRecorder.getSmoothedThroughputPerSec();
        currentRate.set((int) throughput);

        currentConcurrency.updateAndGet(current -> {
            // Vegas Dynamic Thresholds
            int alpha = Math.max(3, (int) (3 * Math.log10(current)));
            int beta = Math.max(6, (int) (6 * Math.log10(current)));

            int next;
            if (queueEstimate > beta) {
                next = (int) Math.max(current * 0.75, 2); // -15%
            } else if (queueEstimate < alpha / 2.0) {
                next = current + Math.max(2, (int) Math.ceil(current * 0.1)); // +10%
            } else if (queueEstimate < alpha) {
                next = current + 1;
            } else { // Little's Law
                double medianLatencySec =
                        latencyRecorder.getPercentile(50.0) / (double) Duration.ofSeconds(1)
                                .toNanos();
                int ideal = (int) Math.ceil(throughput * medianLatencySec);

                // Move 30% of the way to the ideal anchor
                next = current + (int) (0.3 * (ideal - current));
            }

            // Hard bound by the ResourceMonitor's effective limit
            return Math.clamp(next, 2, effectiveConcurrencyLimit.get());
        });

        updateRateLimiter(throughput);
    }

    private void updateRateLimiter(double throughput) {
        if (hardwareUtilization.get().pressure() < 0.90) {
            int targetRps = Math.max(10, (int) throughput);
            int currentRps = rateLimiter.getRateLimiterConfig().getLimitForPeriod();

            // Use a 5% dead-band to prevent jitter
            if (Math.abs(currentRps - targetRps) > (currentRps * 0.05)) {
                rateLimiter.changeLimitForPeriod(targetRps);
            }
        }
    }

    protected void updateEffectiveConcurrencyLimit() {
        HardwareUtilization utilization = this.hardwareUtilization.get();

        double pressure = utilization.pressure();
        double capacity = utilization.availableCpus();

        int baseCap = (int) (capacity * 200);

        // Calculate the Pressure Factor (Quadratic Decay)
        double factor = 1.0;
        if (pressure > 0.70) {
            double x = (pressure - 0.70) / 0.30;
            factor = 1.0 - (0.8 * (x * x));
        }

        int target = (int) Math.max(10, Math.ceil(baseCap * factor));

        // Smooth the Transition (Asymmetric: Drop fast, Grow slow)
        effectiveConcurrencyLimit.updateAndGet(current -> {
            if (target < current) {
                return Math.max(target, (int) Math.ceil(current * 0.85)); // Drop 15%
            } else {
                int delta = target - current;
                return current + Math.max(1, delta / 8); // Rise 1/8 of change
            }
        });
    }

    protected void updateWaiterLimit() {
        int effective = effectiveConcurrencyLimit.get();
        effectiveMaxWaiters.updateAndGet(current -> {
            int target = Math.clamp(effective * 6L, 10, config.maxWaiters);

            if (target < current) {
                int next = (int) Math.ceil(current * 0.8);
                return Math.max(target, next);
            } else {
                int delta = target - current;
                int next = current + Math.max(10, delta / 6);
                return Math.min(target, next);
            }
        });
    }

    protected Mono<Void> handleOverload(int limit) {
        return switch (config.overloadStrategy) {
            case REJECT -> Mono.error(new OverloadException("Wait queue full (cap=" + limit + ")"));
            case DELAY -> {
                double pressure = currentWaiters.get() / (double) effectiveMaxWaiters.get();
                pressure = Math.clamp(pressure, 0.0, 2.0);

                long baseDelay = 2 + ThreadLocalRandom.current().nextLong(3);
                long maxDelay = 50;

                long delayMs = (long) (baseDelay + (maxDelay * pressure * pressure));
                delayMs = Math.clamp(delayMs, baseDelay, maxDelay);

                yield Mono.delay(Duration.ofMillis(delayMs)).then(acquirePermit());
            }
            case DROP_SILENT -> Mono.empty();
        };
    }

    @Override
    public double getPressure() {
        if (circuitBreaker.getState().allowPublish) {
            return 1.0;
        }
        double errorPenalty = Math.min(0.4, recentErrors.get() * 0.05);

        // Thresholds
        int currentLimit = currentConcurrency.get();
        int beta = Math.max(6, (int) (6 * Math.log10(currentLimit)));

        double queueEstimate = latencyRecorder.getVegasQueueEstimate(
                latencyRecorder.getPercentile(50),
                latencyRecorder.getTargetLatencyNs(),
                currentLimit);

        // (Latency-based)
        double vegasPressure = queueEstimate / beta;
        // (Concurrency-based)
        double waiterPressure = (double) waiters.size() / Math.max(1, effectiveMaxWaiters.get());
        // (Resource-based)
        double hardwarePressure = hardwareUtilization.get().pressure();

        // If we have waiters, we are effectively at full capacity.
        // Accumulation of waiters forces the upstream to back off.
        double totalDownstream = (waiters.isEmpty()) ? vegasPressure : 0.8 + (waiterPressure * 0.2);

        return Math.clamp(Math.max(totalDownstream, hardwarePressure) + errorPenalty, 0.0, 1.0);
    }

    @Override
    public int getConcurrencyLimit() {
        return effectiveConcurrencyLimit.get();
    }

    public Flux<Object> addFailureListener() {
        return this.failureBroadcast.asFlux();
    }

    private void updateUtilization(HardwareUtilization utilization) {
        this.hardwareUtilization.set(utilization);
    }

    private void registerPrometheusMetrics() {
        Gauge.builder(config.metricPrefix + ".dispatch.concurrency.current", currentConcurrency,
                        AtomicInteger::get)
                .description("Current adaptive concurrency limit")
                .register(registry);

        Gauge.builder(config.metricPrefix + ".dispatch.inflight.count", inFlight,
                        AtomicInteger::get)
                .description("Number of dispatches being executed")
                .register(registry);

        Gauge.builder(config.metricPrefix + ".dispatch.waiters.count", waiters, Queue::size)
                .description("Number of requests in the waiter queue")
                .register(registry);

        Gauge.builder(config.metricPrefix + ".dispatch.rate.current", currentRate,
                        AtomicInteger::get)
                .description("Current dispatch rate (dispatch/sec)")
                .register(registry);
    }

    public enum OverloadStrategy {
        REJECT,
        DELAY,
        DROP_SILENT
    }

    public record Config(int initialRatePerSecond,
                         int initialConcurrency, int maxWaiters,
                         OverloadStrategy overloadStrategy,
                         LatencyRecorder latencyRecorder, String metricPrefix) {

        public Config(int initialRatePerSecond,
                int initialConcurrency, int maxWaiters,
                OverloadStrategy overloadStrategy) {
            this(initialRatePerSecond, initialConcurrency,
                    maxWaiters, overloadStrategy, null, null);
        }
    }

    public static final class OverloadException extends RuntimeException {

        public OverloadException(String message) {
            super(message);
        }
    }
}
