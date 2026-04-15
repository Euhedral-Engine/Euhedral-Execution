package euhedral.io.resource_monitoring;

import static euhedral.io.utils.MathFunctions.clampDouble;
import static euhedral.io.utils.MathFunctions.clampLong;

import euhedral.io.resource_monitoring.SystemUtilization.HardwareUtilization;
import euhedral.io.resource_monitoring.SystemUtilization.SystemSnapshot;
import euhedral.io.resource_monitoring.providers.OSResourceProviderPicker;
import euhedral.io.resource_monitoring.providers.ResourceProvider;
import euhedral.io.utils.ThreadTimerResolution.Linux;
import java.time.Duration;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public final class ResourceMonitor implements AutoCloseable {

    private static final double NS_TO_SEC = 1.0 / 1_000_000_000.0;

    private final Logger logger = LoggerFactory.getLogger(this.getClass());
    private final Scheduler scheduler;
    private final NumaMapper numaMapper;
    private final long sampleRateNs;

    private final ResourceProvider metrics;

    private final AtomicReference<Double> quotaCpus = new AtomicReference<>(0.0);
    private final AtomicReference<Double> cpuUsageRatio = new AtomicReference<>(0.0);
    private final AtomicReference<Double> cpuThrottleRatio = new AtomicReference<>(0.0);
    private final AtomicReferenceArray<Double> perCpuPressureRatio;
    private final AtomicReferenceArray<Double> perCpuThrottleRatio;
    private final AtomicReference<Double> memUsageRatio = new AtomicReference<>(0.0);
    private final AtomicLong memPerCpuUsageBytes = new AtomicLong(0);
    private final AtomicReference<Double> ioBytesPerSecond = new AtomicReference<>(0.0);
    private final AtomicReference<Double> ioPressure = new AtomicReference<>(0.0);

    private final Sinks.Many<HardwareUtilization> listeners = Sinks.many().multicast()
            .onBackpressureBuffer(1);
    private final AtomicReference<Double> peakIoBps = new AtomicReference<>(1024 * 1024.0);
    private final double smoothingFactor;

    private volatile HardwareUtilization hardwareUtilization;
    private volatile SystemSnapshot snapshot;
    private volatile BitSet globalEffectiveCpus;
    private volatile long lastCpuUsageNs;
    private volatile long lastThrottleNs;
    private volatile long lastIoBytes;
    private volatile long lastWallClockNs;
    private volatile boolean running = false;
    private Thread pollingThread;

    public ResourceMonitor(Duration sampleRate) {
        this.scheduler = Schedulers.boundedElastic();
        this.numaMapper = NumaMapper.INSTANCE;
        this.sampleRateNs = sampleRate.toNanos();
        this.lastWallClockNs = System.nanoTime();
        this.perCpuPressureRatio = new AtomicReferenceArray<>(numaMapper.getCpuCount());
        this.perCpuThrottleRatio = new AtomicReferenceArray<>(numaMapper.getCpuCount());

        double dt = Math.max(1.0, (double) sampleRate.toMillis()) / 1000.0;
        double tau = 3.0; // 3 Seconds
        double smoothingFactor = 1.0 - Math.exp(-dt / tau);

        if (!Double.isFinite(smoothingFactor) || smoothingFactor <= 0) {
            this.smoothingFactor = 0.0645; // Fallback to 1 - e^(-0.2/3.0)
        } else {
            this.smoothingFactor = clampDouble(smoothingFactor, 0.01, 1.0);
        }

        this.metrics = OSResourceProviderPicker.INSTANCE;
        start();
    }

    public void start() {
        if (metrics == null) {
            throw new RuntimeException(
                    "Container metrics not available on this platform. Monitor will not start.");
        }

        if (running) {
            return;
        }
        init();
        poll();

        running = true;
        pollingThread = new Thread(this::runLoop);
        pollingThread.start();
    }

    private void init() {
        this.lastWallClockNs = System.nanoTime();
        SystemSnapshot snapshot = metrics.getSnapshot();

        this.lastCpuUsageNs = snapshot.cpuUsage();
        ;
        this.lastThrottleNs = snapshot.cpuThrottle();
        this.lastIoBytes = snapshot.ioBytes();
    }

    @Override
    public void close() {
        if (running) {
            running = false;
            if (pollingThread != null) {
                try {
                    pollingThread.interrupt();
                    LockSupport.unpark(pollingThread);
                    pollingThread.join(500);
                } catch (Throwable ignored) {

                }
            }
            listeners.tryEmitComplete();
        }
    }

    public HardwareUtilization getUtilization() {
        return hardwareUtilization;
    }

    public SystemSnapshot getSystemSnapshot() {
        return this.snapshot;
    }

    public Flux<HardwareUtilization> addListener() {
        return listeners.asFlux().publishOn(scheduler);
    }

    private void runLoop() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("linux")) {
            Linux.setResolution(1);
        }

        long now;
        while (running && !Thread.interrupted()) {
            now = System.nanoTime();
            poll();
            listeners.tryEmitNext(hardwareUtilization);

            long dT = System.nanoTime() - now;
            long deadline = sampleRateNs + now - dT;
            long temp;
            while ((temp = System.nanoTime()) <= deadline) {
                LockSupport.parkNanos(deadline - temp);
            }
        }
        close();
    }

    private void poll() {
        try {
            this.snapshot = metrics.getSnapshot();
            updateCpu(snapshot);
            updateMemory(snapshot);
            updateIO(snapshot);
            lastWallClockNs = snapshot.timeNs();

            long memoryLimit = clampLong(snapshot.memoryLimit(), 0, 1_000_000_000_000_000L);
            hardwareUtilization = new HardwareUtilization(lastWallClockNs, quotaCpus.get(),
                    cpuUsageRatio.get(),
                    snapshot.period(),
                    globalEffectiveCpus,
                    perCpuPressureRatio,
                    cpuThrottleRatio.get(), perCpuThrottleRatio,
                    memoryLimit,
                    memoryLimit / snapshot.availableCpus(),
                    memUsageRatio.get(),
                    memPerCpuUsageBytes.get(),
                    ioBytesPerSecond.get(), ioPressure.get(), snapshot);
            if (numaMapper != null) {
                numaMapper.update(hardwareUtilization);
            }
        } catch (Exception e) {
            logger.error("Failed to update utilization", e);
        }
    }

    // CPU

    private void updateCpu(SystemSnapshot snapshot) {
        long deltaUsage = snapshot.cpuUsage() - lastCpuUsageNs;
        long deltaThrottle = snapshot.cpuThrottle() - lastThrottleNs;
        long deltaTime = Math.max(snapshot.timeNs() - lastWallClockNs,
                Duration.ofMillis(10).toNanos());

        if (deltaTime <= 0) {
            return;
        }

        double rawCpuUtil = deltaUsage / (double) deltaTime / snapshot.quotaCpus();
        double rawThrottle = deltaThrottle / (double) deltaTime;
        updatePerCpuUtilization(deltaTime, deltaThrottle, snapshot);

        lastCpuUsageNs = snapshot.cpuUsage();
        lastThrottleNs = snapshot.cpuThrottle();
        cpuUsageRatio.updateAndGet(
                old -> ewma(old, !Double.isFinite(rawCpuUtil) ? 0.0 : rawCpuUtil));
        cpuThrottleRatio.updateAndGet(
                old -> ewma(old, !Double.isFinite(rawThrottle) ? 0.0 : rawThrottle));
        this.quotaCpus.set(snapshot.quotaCpus());
    }

    private void updatePerCpuUtilization(long deltaTimeNs, long deltaTotalThrottleNs,
            SystemSnapshot snapshot) {
        BitSet effective = snapshot.effectiveCpus();
        double[] currentPressureSnapshot = snapshot.pressurePerCpu();

        if (effective == null || currentPressureSnapshot == null) {
            return;
        }

        double totalThrottleRatio = (double) deltaTotalThrottleNs / deltaTimeNs;
        double available = quotaCpus.get();

        for (int i = effective.nextSetBit(0); i >= 0; i = effective.nextSetBit(i + 1)) {
            double deltaPressureNs = currentPressureSnapshot[i] * 1000.0;

            double cpuPressureRatio = deltaPressureNs / deltaTimeNs;

            double cpuThrottle = (available > 0)
                    ? (cpuPressureRatio / available) * totalThrottleRatio
                    : 0;

            updateRatio(perCpuPressureRatio, i, Math.max(0, cpuPressureRatio));
            updateRatio(perCpuThrottleRatio, i, Math.max(0, cpuThrottle));
        }
        this.globalEffectiveCpus = effective;
    }

    // Memory

    private void updateMemory(SystemSnapshot snapshot) {
        long workingMemory = Math.max(0, snapshot.memoryUsage() - snapshot.inactiveFileMemory());

        if (snapshot.memoryLimit() > 0 && snapshot.memoryLimit() < 1_000_000_000_000_000L) {
            double workingMemoryUtil = (double) workingMemory / snapshot.memoryLimit();

            double availableCpus = snapshot.availableCpus();

            if (Double.isFinite(workingMemoryUtil)) {
                memUsageRatio.updateAndGet(
                        old -> ewma(old, clampDouble(workingMemoryUtil, 0.0, 1.0)));

                if (availableCpus > 0) {
                    // Density relative to the physical core count visible in the container
                    this.memPerCpuUsageBytes.set(
                            (long) (((double) workingMemory / snapshot.memoryLimit())
                                    * availableCpus));
                }
            }
        }
    }

    // IO
    private void updateIO(SystemSnapshot snapshot) {
        long deltaTimeNs = snapshot.timeNs() - lastWallClockNs;

        if (deltaTimeNs > 0) {
            double deltaTimeSec = deltaTimeNs * NS_TO_SEC;

            // Calculate raw Bytes Per Second
            long deltaBytes = (lastIoBytes == 0) ? 0 : snapshot.ioBytes() - lastIoBytes;
            double rawBps = deltaBytes / deltaTimeSec;

            ioBytesPerSecond.updateAndGet(old -> ewma(old, rawBps));
            peakIoBps.updateAndGet(peak -> Math.max(rawBps, peak * 0.9999));

            double currentPeak = peakIoBps.get();
            double rawIoRatio = (currentPeak > 0) ? ioBytesPerSecond.get() / currentPeak : 0.0;

            lastIoBytes = snapshot.ioBytes();
            ioPressure.set(clampDouble(rawIoRatio, 0.0, 1.0));
        }
    }

    private void updateRatio(AtomicReferenceArray<Double> array, int index, double newValue) {
        array.updateAndGet(index, old -> ewma(old == null ? 0.0 : old, newValue));
    }

    private double ewma(Double oldVal, Double newVal) {
        if (newVal == null) {
            return 0.0;
        }

        double clampedNew = clampDouble(newVal, 0.0, 1.0);

        if (oldVal == null || oldVal <= 0) {
            return clampedNew;
        }

        return (smoothingFactor * clampedNew) + (1 - smoothingFactor) * oldVal;
    }
}
