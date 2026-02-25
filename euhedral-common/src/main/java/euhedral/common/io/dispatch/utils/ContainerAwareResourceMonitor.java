package euhedral.common.io.dispatch.utils;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import jdk.internal.platform.Container;
import jdk.internal.platform.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

public final class ContainerAwareResourceMonitor implements ResourceMonitor, AutoCloseable {

    private final Metrics metrics = Container.metrics();

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final AtomicReference<Double> availableCpus = new AtomicReference<>(0.0);
    private final AtomicReference<Double> cpuUsageRatio = new AtomicReference<>(0.0);
    private final AtomicReference<Double> cpuThrottleRatio = new AtomicReference<>(0.0);
    private final AtomicReference<Double> memUsageRatio = new AtomicReference<>(0.0);
    private final AtomicReference<Double> ioBytesPerSecond = new AtomicReference<>(0.0);
    private final AtomicReference<Double> ioPressure = new AtomicReference<>(0.0);

    private final Sinks.Many<HardwareUtilization> listeners = Sinks.many().multicast()
            .onBackpressureBuffer();

    private final AtomicReference<Double> peakIoBps = new AtomicReference<>(1024 * 1024.0);
    private volatile long lastCpuUsageNs = 0;
    private volatile long lastThrottleNs = 0;
    private volatile long lastIoBytes = 0;
    private volatile long lastWallClockNs;
    private volatile boolean running;

    private final LongSupplier timeSupplierNs;
    private final Duration sampleRate;
    private final double smoothingFactor;
    private Thread pollingThread;

    public ContainerAwareResourceMonitor(Duration sampleRate) {
        this(sampleRate,
                () -> Duration.ofMillis(System.currentTimeMillis()).toNanos());
    }

    public ContainerAwareResourceMonitor(Duration sampleRate,
            LongSupplier timeSupplierNs) {
        this.sampleRate = sampleRate;
        this.timeSupplierNs = timeSupplierNs;
        this.lastWallClockNs = timeSupplierNs.getAsLong();

        double dt = Math.max(1.0, (double) sampleRate.toMillis()) / 1000.0;
        double tau = 3.0;
        double smoothingFactor = 1.0 - Math.exp(-dt / tau);

        if (!Double.isFinite(smoothingFactor) || smoothingFactor <= 0) {
            this.smoothingFactor = 0.0645; // Fallback to 1 - e^(-0.2/3.0)
        } else {
            this.smoothingFactor = Math.clamp(smoothingFactor, 0.01, 1.0);
        }

        start();
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;

        pollingThread = Thread.startVirtualThread(this::runLoop);
    }

    @Override
    public void close() {
        running = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
    }

    @Override
    public double availableCpus() {
        return availableCpus.get();
    }

    @Override
    public double cpuUtilization() {
        return cpuUsageRatio.get();
    }

    @Override
    public double cpuThrottleRatio() {
        return cpuThrottleRatio.get();
    }

    @Override
    public double memoryUtilization() {
        return memUsageRatio.get();
    }

    @Override
    public double ioBytesPerSecond() {
        return ioBytesPerSecond.get();
    }

    @Override
    public double ioPressure() {
        return ioPressure.get();
    }

    @Override
    public HardwareUtilization getUtilization() {
        return new HardwareUtilization(availableCpus(), cpuUtilization(), memoryUtilization(),
                cpuThrottleRatio(),
                ioBytesPerSecond(), ioPressure.get());
    }

    @Override
    public Flux<HardwareUtilization> addListener() {
        return listeners.asFlux();
    }

    private void runLoop() {
        while (running) {
            poll();
            listeners.tryEmitNext(getUtilization());

            try {
                Thread.sleep(sampleRate);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void poll() {
        try {
            updateCpu();
            updateMemory();
            updateIO();
            lastWallClockNs = timeSupplierNs.getAsLong();
        } catch (Exception e) {
            logger.error("Failed to update utilization", e);
        }
    }

    // CPU

    private void updateCpu() {
        long quota = metrics.getCpuQuota();
        long period = metrics.getCpuPeriod();
        double availableCpus;

        if (quota > 0 && period > 0) {
            availableCpus = (double) quota / period;
        } else {
            availableCpus = Runtime.getRuntime().availableProcessors();
        }

        long now = timeSupplierNs.getAsLong();
        long cpuUsage = metrics.getCpuUsage();
        long cpuThrottle = metrics.getCpuThrottledTime();

        long deltaUsage = cpuUsage - lastCpuUsageNs;
        long deltaThrottle = cpuThrottle - lastThrottleNs;
        long deltaTime = now - lastWallClockNs;

        double rawCpuUtil = deltaUsage / (double) deltaTime / availableCpus;
        double rawThrottle = deltaThrottle / (double) deltaTime;

        lastCpuUsageNs = cpuUsage;
        lastThrottleNs = cpuThrottle;
        cpuUsageRatio.updateAndGet(
                old -> ewma(old, !Double.isFinite(rawCpuUtil) ? 0.0 : rawCpuUtil));
        cpuThrottleRatio.updateAndGet(
                old -> ewma(old, !Double.isFinite(rawThrottle) ? 0.0 : rawThrottle));
        this.availableCpus.set(availableCpus);
    }

    // Memory

    private void updateMemory() {
        long usage = metrics.getMemoryUsage();
        long max = metrics.getMemoryLimit();

        if (max > 0 && max < 1_000_000_000_000_000L) {
            double rawUtil = (double) usage / max;
            if (Double.isFinite(rawUtil)) {
                memUsageRatio.updateAndGet(old -> ewma(old, rawUtil));
            }
        }
    }

    // IO
    private void updateIO() {
        long ioBytes = metrics.getBlkIOServiced();
        long now = timeSupplierNs.getAsLong();
        double deltaTime = now - lastWallClockNs;

        if (deltaTime > 0) {
            long delta = (lastIoBytes == 0) ? 0 : ioBytes - lastIoBytes;
            double rawBps = (double) delta / (deltaTime / TimeUnit.SECONDS.toNanos(1));

            lastIoBytes = ioBytes;
            ioBytesPerSecond.updateAndGet(old -> {
                if (old <= 0) {
                    return rawBps;
                }
                return (smoothingFactor * rawBps) + (1 - smoothingFactor) * old;
            });

            peakIoBps.updateAndGet(peak -> Math.max(ioBytes, peak * 0.9999));
            double rawIoRatio = ioBytes / peakIoBps.get();

            ioPressure.set(Math.clamp(rawIoRatio, 0.0, 1.0));
        }
    }

    private double ewma(double oldVal, double newVal) {
        double clampedNew = Math.clamp(newVal, 0.0, 1.0);

        if (oldVal <= 0) {
            return clampedNew;
        }

        return (smoothingFactor * clampedNew) + (1 - smoothingFactor) * oldVal;
    }
}
