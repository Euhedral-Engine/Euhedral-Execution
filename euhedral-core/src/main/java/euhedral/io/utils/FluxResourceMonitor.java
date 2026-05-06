package euhedral.io.utils;

import euhedral.hardware_utils.ResourceMonitor;
import euhedral.hardware_utils.common.SystemUtilization.HardwareUtilization;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.EmitResult;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public final class FluxResourceMonitor implements Disposable, AutoCloseable {

    private final Scheduler scheduler;

    private final ResourceMonitor resourceMonitor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Sinks.Many<HardwareUtilization> listeners = Sinks.unsafe().many().multicast()
            .onBackpressureBuffer(1);

    public FluxResourceMonitor() {
        this(Duration.ofMillis(200));
    }

    public FluxResourceMonitor(Duration sampleRate) {
        this.scheduler = Schedulers.boundedElastic();
        this.resourceMonitor = new ResourceMonitor(sampleRate);
        this.resourceMonitor.addListener(utilization -> {
            EmitResult result;
            do {
                result = listeners.tryEmitNext(utilization);
                if (result == EmitResult.FAIL_CANCELLED || result == EmitResult.FAIL_ZERO_SUBSCRIBER
                        || result == EmitResult.FAIL_TERMINATED) {
                    break;
                }
            } while (result.isFailure());
        });
    }

    public void start() {
        if (this.closed.get()) {
            throw new IllegalStateException("This FluxResourceMonitor is closed.");
        }
        this.resourceMonitor.start();
    }

    @Override
    public void dispose() {
        close();
    }

    @Override
    public boolean isDisposed() {
        return this.closed.get();
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.resourceMonitor.close();
            this.listeners.tryEmitComplete();
        }
    }

    public HardwareUtilization getUtilization() {
        return this.resourceMonitor.getUtilization();
    }

    public Flux<HardwareUtilization> addListener() {
        return this.listeners.asFlux().publishOn(scheduler);
    }
}
