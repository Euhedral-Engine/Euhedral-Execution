package euhedral.io.reactor;

import euhedral.atomics.PaddedAtomicLong;
import euhedral.io.control_plane.ControlPlane;
import euhedral.io.impl.DefaultCloneablePipeline;
import euhedral.io.reactor.common.EuhedralSubscriber;
import euhedral.io.utils.MathFunctions;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@SuppressWarnings({"resource", "unused"})
public class EuhedralScheduler implements Scheduler {

    private static final AtomicReference<EuhedralScheduler> INSTANCE = new AtomicReference<>();
    private static final AtomicBoolean CONSTRUCTING = new AtomicBoolean(false);
    private static final PaddedAtomicLong SEED = new PaddedAtomicLong(ThreadLocalRandom.current()
            .nextLong());

    public static @Nullable EuhedralScheduler get() {
        return INSTANCE.getOpaque();
    }

    public static @NonNull EuhedralScheduler getOrCreate() {
        return getOrCreate("EuhedralScheduler", null, null);
    }

    public static @NonNull EuhedralScheduler getOrCreate(String name, @Nullable String metricPrefix,
            @Nullable MeterRegistry meterRegistry) {
        EuhedralScheduler instance = INSTANCE.getOpaque();
        if (instance != null) {
            return instance;
        }

        if (CONSTRUCTING.compareAndSet(false, true)) {
            ControlPlane controlPlane = ControlPlane.getOrCreate(name,
                    new DefaultCloneablePipeline(name + "Pipeline", metricPrefix, meterRegistry),
                    meterRegistry);
            instance = new EuhedralScheduler(controlPlane);
            INSTANCE.set(instance);
            return instance;
        }

        while ((instance = INSTANCE.getOpaque()) == null) {
            Thread.onSpinWait();
        }
        return instance;
    }

    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final ControlPlane controlPlane;
    private final EuhedralWorker[] sinks;

    private EuhedralScheduler(ControlPlane controlPlane) {
        this.controlPlane = controlPlane;
        this.sinks = new EuhedralWorker[Runtime.getRuntime().availableProcessors()];
        for (int i = 0; i < this.sinks.length; i++) {
            this.sinks[i] = EuhedralWorker.spawn(8_096, 2);
            controlPlane.ingest(this.sinks[i]);
        }
    }

    /// This method injects a Subscriber handle into the Euhedral ControlPlane. The subscriber must
    /// have a subscription before this method is called. This is equivalent to calling
    /// `.publishOn()`
    ///
    /// This is the most efficient way to use this scheduler. [EuhedralOperator] automatically
    /// invokes this for you.
    ///
    /// Example Usage:
    /// ```java
    /// EuhedralSubscriber subscriber = new EuhedralSubscriber();
    /// flux.subscribe(subscriber);
    /// scheduler.ingest(subscriber);
    /// ```
    public void ingest(@NonNull EuhedralSubscriber subscriber) {
        Objects.requireNonNull(subscriber);
        if (subscriber.hasSubscription()) {
            this.controlPlane.ingest(subscriber);
        }
        throw new IllegalStateException(
                "The subscriber must have a subscription before calling ingest");
    }

    @Override
    public @NonNull Disposable schedule(@NonNull Runnable task) {
        return getSink().schedule(task);
    }

    @Override
    public @NonNull Disposable schedule(@NonNull Runnable task, long delay,
            @NonNull TimeUnit unit) {
        return getSink().schedule(task, delay, unit);
    }

    @Override
    public @NonNull Disposable schedulePeriodically(@NonNull Runnable task,
            long initialDelay, long period,
            @NonNull TimeUnit unit) {
        return getSink().schedulePeriodically(task, initialDelay, period, unit);
    }

    @Override
    public long now(TimeUnit unit) {
        return unit.convert(System.nanoTime(), TimeUnit.NANOSECONDS);
    }

    @Override
    public @NonNull Worker createWorker() {
        EuhedralWorker worker = EuhedralWorker.spawn(8_096, 2);
        this.controlPlane.ingest(worker);
        return worker;
    }

    @Override
    public void dispose() {
        if (this.disposed.compareAndSet(false, true)) {
            for (EuhedralWorker sink : sinks) {
                sink.close();
            }
        }
    }

    @Override
    public boolean isDisposed() {
        return this.disposed.getOpaque();
    }

    @Override
    public @NonNull Mono<Void> disposeGracefully() {
        return Mono.fromRunnable(this::dispose);
    }

    @Override
    public void init() {
        Scheduler.super.init();
    }

    private EuhedralWorker getSink() {
        long rand = SEED.getAndAddRelease(1);
        int idx = (int) MathFunctions.unsignedMultiplyHigh(rand, this.sinks.length);
        return this.sinks[idx];
    }
}
