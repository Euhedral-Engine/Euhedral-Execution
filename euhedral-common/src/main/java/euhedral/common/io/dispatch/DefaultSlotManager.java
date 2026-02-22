package euhedral.common.io.dispatch;

import euhedral.common.io.dispatch.interfaces.SlotManager;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import lombok.AccessLevel;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.One;
import reactor.core.scheduler.Scheduler;
import reactor.util.retry.Retry;

@Getter(AccessLevel.PROTECTED)
public class DefaultSlotManager implements SlotManager {

    @Getter
    protected final int maxRatePerSecond;
    @Getter
    protected final int minRatePerSecond;
    @Getter
    protected final int concurrencyLimit;
    @Getter
    protected final long targetLatencyNs;

    // State
    protected final AtomicInteger inFlight = new AtomicInteger(0);
    protected final AtomicInteger currentConcurrency = new AtomicInteger(0);
    protected final AtomicInteger currentRate = new AtomicInteger(0);

    protected final AtomicLong baseLatencyNs = new AtomicLong(Long.MAX_VALUE);
    protected final AtomicLong windowStartNs = new AtomicLong(0);
    protected final AtomicLong windowMinLatencyNs = new AtomicLong(Long.MAX_VALUE);
    protected final ConcurrentLinkedDeque<One<Void>> waiters = new ConcurrentLinkedDeque<>();
    protected final Scheduler scheduler;

    // Metrics
    protected final AtomicLong lastMeasuredLatencyNs = new AtomicLong(500_000_000L);
    protected final AtomicLong previousWindowCount = new AtomicLong(0);
    protected final AtomicLong currentWindowCount = new AtomicLong(0);
    protected final AtomicLong measurementCount = new AtomicLong();
    protected final CircuitBreaker circuitBreaker;
    // Prometheus
    private final MeterRegistry registry;
    private final Timer dispatchTimer;
    private final Timer slotAcquisitionTimer;
    // Resilience
    protected volatile RateLimiter rateLimiter;

    public DefaultSlotManager(int initialRatePerSecond, int minRatePerSecond, int maxRatePerSecond,
            int initialConcurrency, int concurrencyLimit,
            @NonNull Duration targetLatency,
            @NonNull CircuitBreaker circuitBreaker,
            @NonNull Scheduler scheduler) {
        this(initialRatePerSecond, minRatePerSecond, maxRatePerSecond, initialConcurrency,
                concurrencyLimit, targetLatency, circuitBreaker, scheduler, null);
    }

    public DefaultSlotManager(int initialRatePerSecond, int minRatePerSecond, int maxRatePerSecond,
            int initialConcurrency, int concurrencyLimit,
            @NonNull Duration targetLatency,
            @NonNull CircuitBreaker circuitBreaker,
            @NonNull Scheduler scheduler, MeterRegistry registry) {
        this.maxRatePerSecond = maxRatePerSecond;
        this.minRatePerSecond = minRatePerSecond;
        this.concurrencyLimit = concurrencyLimit;
        this.scheduler = scheduler;
        this.targetLatencyNs = targetLatency.toNanos();

        this.currentRate.set(initialRatePerSecond);
        this.currentConcurrency.set(initialConcurrency);

        this.rateLimiter = buildRateLimiter(initialRatePerSecond);
        this.circuitBreaker = circuitBreaker;
        this.registry = registry;

        if (registry != null) {
            this.dispatchTimer = Timer.builder("dispatch.latency")
                    .description("Latency of dispatched work including queue time")
                    .publishPercentileHistogram() // Enable for Prometheus heatmaps
                    .register(registry);
            this.slotAcquisitionTimer = Timer.builder("slot.acquisition.latency")
                    .description("Latency of acquiring a slot")
                    .publishPercentileHistogram()
                    .register(registry);
            registerPrometheusMetrics();
        } else {
            this.dispatchTimer = null;
            this.slotAcquisitionTimer = null;
        }
    }

    protected RateLimiter buildRateLimiter(int ratePerSecond) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(ratePerSecond)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();

        return RateLimiter.of("defaultSlotManager", config);
    }

    protected <T> UnaryOperator<Publisher<T>> getOperator() {
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
        Timer.Sample sample = registry == null ? null : Timer.start(registry);
        long startNs = scheduler.now(TimeUnit.NANOSECONDS);

        AtomicBoolean permitAcquired = new AtomicBoolean(false);

        return Mono.defer(work)
                .transformDeferred(getOperator())
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .doOnSuccess(o -> currentWindowCount.incrementAndGet())
                .as(mono -> acquirePermit()
                        .doOnSuccess(v -> permitAcquired.set(true))
                        .then(mono)
                        .doFinally(sig -> {
                            if (permitAcquired.get()) {
                                releasePermit();
                            }
                        }))
                .doFinally(sig -> {
                    if (sample != null) {
                        sample.stop(dispatchTimer);
                    }

                    long durationNs = this.scheduler.now(TimeUnit.NANOSECONDS) - startNs;
                    updateMetrics(durationNs);
                });
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

            Sinks.One<Void> waiter = Sinks.one();
            waiters.add(waiter);

            if (tryAcquireAfterEnqueue(waiter)) {
                if (sample != null) {
                    sample.stop(slotAcquisitionTimer);
                }
                return Mono.empty(); // Success
            }
            return waiter.asMono()
                    .timeout(Duration.ofSeconds(30), scheduler)
                    .onErrorResume(TimeoutException.class, e ->
                            Mono.error(new RuntimeException(
                                    "Timed out waiting for concurrency slot")))
                    .doOnSuccess(v -> {
                        if (sample != null) {
                            sample.stop(slotAcquisitionTimer);
                        }
                    })
                    .doOnCancel(() -> waiters.remove(waiter))
                    .doOnError(e -> waiters.remove(waiter));
        });
    }

    protected void releasePermit() {
        inFlight.decrementAndGet();

        Sinks.One<Void> waiter = waiters.poll();
        if (waiter != null) {
            waiter.tryEmitEmpty();
        }
    }

    protected boolean tryAcquireAfterEnqueue(Sinks.One<Void> waiter) {
        while (true) {
            int limit = currentConcurrency.get();
            int current = inFlight.get();

            if (current >= limit) {
                return false;
            }

            if (inFlight.compareAndSet(current, current + 1)) {
                waiters.remove(waiter);
                return true;
            }
        }
    }

    protected void drainWaiters() {
        while (inFlight.get() < currentConcurrency.get()) {
            Sinks.One<Void> waiter = waiters.poll();
            if (waiter == null) {
                break;
            }

            if (inFlight.incrementAndGet() <= currentConcurrency.get()) {
                waiter.tryEmitEmpty();
            } else {
                inFlight.decrementAndGet();
                waiters.add(waiter);
                break;
            }
        }
    }

    protected void updateMetrics(long latencyNs) {
        long count = measurementCount.get();

        if (count <= 15) {
            measurementCount.incrementAndGet();
            lastMeasuredLatencyNs.set(latencyNs);
        } else {
            long prev = lastMeasuredLatencyNs.get();
            long smoothed = (long) (prev * 0.9 + latencyNs * 0.1);
            lastMeasuredLatencyNs.set(smoothed);
        }

        windowMinLatencyNs.updateAndGet(prev -> Math.min(prev, latencyNs));
        long minLatency = windowMinLatencyNs.get();
        baseLatencyNs.updateAndGet(prev -> Math.min(prev, minLatency));

        double queueEstimate = getVegasQueueEstimate(latencyNs);
        updateConcurrency(queueEstimate);

        tryResetWindow();
    }

    protected double getVegasQueueEstimate(long latencyNs) {
        long base = baseLatencyNs.get();

        if (latencyNs < base * 0.9 || base == Long.MAX_VALUE) {
            baseLatencyNs.set(latencyNs);
            return 0.0;
        }

        if (latencyNs <= base) {
            return 0.0;
        }

        // Standard Vegas calculation: (Expected - Actual) / Expected
        // Simplified to: Concurrency * (Latency - Base) / Latency
        if (latencyNs > 0) {
            return (double) currentConcurrency.get() * (latencyNs - base) / (double) latencyNs;
        }

        return 0.0;
    }

    protected void updateConcurrency(double queueEstimate) {
        currentConcurrency.updateAndGet(current -> {
            // Calculate dynamic thresholds based on current capacity
            int vegasAlpha = (int) (3 * Math.log10(current));
            int vegasBeta = (int) (6 * Math.log10(current));

            vegasAlpha = Math.max(3, vegasAlpha);
            vegasBeta = Math.max(6, vegasBeta);

            double throughput = getSmoothedThroughput();
            currentRate.set((int) throughput);

            if (this.rateLimiter.getRateLimiterConfig().getLimitForPeriod() != (int) throughput) {
                this.rateLimiter = buildRateLimiter((int) throughput);
            }

            int nextConcurrency;

            // Drop 25% if the queue is backed up
            if (queueEstimate > vegasBeta) {
                nextConcurrency = (int) Math.max(1, Math.floor(current * 0.75));
            }
            // Aggressive increase if under-utilized
            else if (queueEstimate < (vegasAlpha / 2.0) && measurementCount.get() > 15) {
                int boost = Math.max(2, (int) Math.ceil(current * 0.1)); // max(2, 10%)
                nextConcurrency = current + boost;
            }
            // Normal increase
            else if (queueEstimate < vegasAlpha) {
                nextConcurrency = current + 1;
            } else {
                // Use Little's Law as a target when stable
                double latencySec = lastMeasuredLatencyNs.get() / 1_000_000_000.0;
                int ideal = (int) Math.ceil(throughput * latencySec);

                // Slow low-pass filter to prevent jitter
                nextConcurrency = current + (int) (0.3 * (ideal - current));
            }

            // Apply bounds and drain waiting threads
            int finalLimit = Math.max(2, Math.min(nextConcurrency, concurrencyLimit));
            drainWaiters();
            return finalLimit;
        });
    }

    protected double getSmoothedThroughput() {
        long nowNs = scheduler.now(TimeUnit.NANOSECONDS);
        double weight = (nowNs - windowStartNs.get()) / 1_000_000_000.0;

        double effectiveCount =
                (previousWindowCount.get() * (1.0 - weight)) + currentWindowCount.get();

        return Math.max(1.0, effectiveCount);
    }

    protected void tryResetWindow() {
        long nowNs = scheduler.now(TimeUnit.NANOSECONDS);
        long windowDurationNs = nowNs - windowStartNs.get();

        if (windowDurationNs >= 1_000_000_000L) {
            // Rotate the windows
            previousWindowCount.set(currentWindowCount.get());
            currentWindowCount.set(0);
            windowStartNs.set(nowNs);

            windowMinLatencyNs.set(Long.MAX_VALUE);
        }
    }

    private void registerPrometheusMetrics() {
        Gauge.builder("dispatch.concurrency.current", currentConcurrency, AtomicInteger::get)
                .description("Current adaptive concurrency limit")
                .register(registry);

        Gauge.builder("dispatch.inflight.count", inFlight, AtomicInteger::get)
                .description("Number of dispatches being executed")
                .register(registry);

        Gauge.builder("dispatch.waiters.count", waiters, Queue::size)
                .description("Number of requests in the waiter queue")
                .register(registry);

        Gauge.builder("dispatch.rate.current", currentRate, AtomicInteger::get)
                .description("Current dispatch rate (dispatch/sec)")
                .register(registry);
    }
}
