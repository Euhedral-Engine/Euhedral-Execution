package euhedral.hardware_utils;

import euhedral.hardware_utils.common.SystemUtilization.HardwareUtilization;
import euhedral.hardware_utils.common.SystemUtilization.SystemSnapshot;
import euhedral.hardware_utils.common.UnmodifiableBitSet;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import java.time.Duration;
import java.util.BitSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ResourceMonitor implements AutoCloseable {

    // 1M GB to represent unlimited memory limit.
    private static final long MEMORY_CLAMP = 1_000_000_000_000_000L;
    private static final double NS_TO_SEC = 1.0 / 1_000_000_000.0;

    private static long clampMemory(long val) {
        return Math.min(MEMORY_CLAMP, Math.max(0, val));
    }

    private static double clampDouble(double val, double min, double max) {
        return Math.min(max, Math.max(min, val));
    }

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final long sampleRateNs;
    private final double smoothingFactor;

    private final AtomicBoolean listenerGuard = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closing = new AtomicBoolean(false);

    private final ObjectArraySet<MonitorListener> listeners = new ObjectArraySet<>(1);
    private final Readings readings = new Readings();

    private volatile HardwareUtilization hardwareUtilization;
    private volatile BitSet globalEffectiveCpus;
    private volatile long lastCpuUsageNs;
    private volatile long lastThrottleNs;
    private volatile long lastDiskIOBytes;
    private volatile Thread pollingThread;

    public ResourceMonitor() {
        this(Duration.ofMillis(200));
    }

    public ResourceMonitor(Duration sampleRate) {
        this.sampleRateNs = sampleRate.toNanos();

        double dt = Math.max(1, sampleRate.toMillis()) / 1000d;
        double tau = 3d; // 3 Seconds
        double smoothingFactor = 1d - Math.exp(-dt / tau);

        if (!Double.isFinite(smoothingFactor) || smoothingFactor <= 0) {
            this.smoothingFactor = 0.0645; // Fallback to 1 - e^(-0.2/3.0)
        } else {
            this.smoothingFactor = clampDouble(smoothingFactor, 0.01, 1.0);
        }

        start();
    }

    public void start() {
        if (SystemInfo.SNAPSHOTTER == null) {
            throw new RuntimeException(
                    "Resource monitoring not available on this platform. Monitor will not start.");
        }
        while (closing.get()) {
            Thread.onSpinWait();
        }

        if (!running.compareAndSet(false, true)) {
            return;
        }

        init();
        poll();

        pollingThread = new Thread(this::runLoop);
        pollingThread.start();
    }

    private void init() {
        this.readings.lastWallClockNs = System.nanoTime();
        SystemSnapshot snapshot = SystemInfo.getSystemSnapshot();

        this.lastCpuUsageNs = snapshot.cpuUsage();

        this.lastThrottleNs = snapshot.cpuThrottle();
        this.lastDiskIOBytes = snapshot.diskIOBytes();
    }

    @Override
    public void close() {
        if (this.closing.compareAndSet(false, true)) {
            if (this.running.compareAndSet(true, false)) {
                try {
                    Thread t = this.pollingThread;
                    t.interrupt();
                    LockSupport.unpark(t);
                    t.join(500);
                } catch (Throwable ignored) {

                } finally {
                    this.pollingThread = null;
                }
            }
            this.closing.set(false);
        }
    }

    /// Adds a listener to this monitor to be updated asynchronously.
    public void addListener(MonitorListener listener) {
        while (!this.listenerGuard.compareAndSet(false, true)) {
            Thread.onSpinWait();
        }
        this.listeners.add(listener);
        this.listenerGuard.set(false);
    }

    /// Iterates through the listeners and gives them the new HardwareUtilization record.
    private void updateListeners(HardwareUtilization utilization) {
        CompletableFuture.runAsync(() -> {
            while (!this.listenerGuard.compareAndSet(false, true)) {
                Thread.onSpinWait();
            }
            for (MonitorListener listener : this.listeners) {
                try {
                    listener.update(utilization);
                } catch (Throwable ignored) {

                }
            }
            this.listenerGuard.set(false);
        });
    }

    public final HardwareUtilization getUtilization() {
        return this.hardwareUtilization;
    }

    private void runLoop() {
        ThreadTools.setTimerResolution(1);

        long now;
        while (this.running.get() && !Thread.interrupted()) {
            now = System.nanoTime();
            poll();
            updateListeners(this.hardwareUtilization);

            long dT = System.nanoTime() - now;
            long deadline = this.sampleRateNs + now - dT;
            long temp;
            while ((temp = System.nanoTime()) <= deadline) {
                LockSupport.parkNanos(deadline - temp);
            }
        }
        close();
    }

    private void poll() {
        try {
            SystemSnapshot snapshot = SystemInfo.getSystemSnapshot();

            updateCpu(snapshot);
            updateMemory(snapshot);
            updateDiskIO(snapshot);
            this.readings.lastWallClockNs = snapshot.timeNs();

            long memoryLimit = clampMemory(snapshot.memoryLimit());
            hardwareUtilization = HardwareUtilization.create(this.readings.lastWallClockNs,
                    this.readings.quotaCpus,
                    this.readings.cpuUsageRatio,
                    snapshot.period(),
                    UnmodifiableBitSet.wrap(this.globalEffectiveCpus),
                    this.readings.cpuThrottleRatio,
                    this.readings.perCpuThrottleRatio,
                    this.readings.perCpuPressureRatio,
                    memoryLimit,
                    memoryLimit / snapshot.totalCpus(),
                    this.readings.memUsageRatio,
                    this.readings.memPerCpuUsageBytes,
                    this.readings.diskIOBytesPerSecond, this.readings.diskIOPressure,
                    snapshot
            );
            EffectiveTopology.update(this.hardwareUtilization);
        } catch (Exception e) {
            logger.error("Failed to update utilization", e);
        }
    }

    // ----- CPU -----

    private void updateCpu(SystemSnapshot snapshot) {
        long deltaUsage = snapshot.cpuUsage() - this.lastCpuUsageNs;
        long deltaThrottle = snapshot.cpuThrottle() - this.lastThrottleNs;
        long deltaTime = Math.max(snapshot.timeNs() - this.readings.lastWallClockNs,
                Duration.ofMillis(10).toNanos());

        if (deltaTime <= 0) {
            return;
        }

        double rawCpuUtil = deltaUsage / (double) deltaTime / snapshot.quotaCpus();
        double rawThrottle = deltaThrottle / (double) deltaTime;
        updatePerCpuUtilization(deltaTime, deltaThrottle, snapshot);

        this.lastCpuUsageNs = snapshot.cpuUsage();
        this.lastThrottleNs = snapshot.cpuThrottle();
        this.readings.cpuUsageRatio = ewma(this.readings.cpuUsageRatio,
                !Double.isFinite(rawCpuUtil) ? 0.0 : rawCpuUtil);
        this.readings.cpuThrottleRatio = ewma(this.readings.cpuUsageRatio,
                !Double.isFinite(rawThrottle) ? 0.0 : rawThrottle);
        this.readings.quotaCpus = snapshot.quotaCpus();
    }

    private void updatePerCpuUtilization(long deltaTimeNs, long deltaTotalThrottleNs,
            SystemSnapshot snapshot) {
        BitSet effective = (BitSet) snapshot.effectiveCpus().clone();

        if (effective == null) {
            return;
        }

        double invDeltaTimeNs = 1.0 / deltaTimeNs;
        double totalThrottleRatio = deltaTotalThrottleNs * invDeltaTimeNs;
        double available = this.readings.quotaCpus;

        double throttleScale = (available > 0) ? (totalThrottleRatio / available) : 0;

        for (int i = effective.nextSetBit(0); i >= 0; i = effective.nextSetBit(i + 1)) {
            double deltaPressureNs = snapshot.pressurePerCpu().get(i) * 1000.0;
            double cpuPressureRatio = deltaPressureNs * invDeltaTimeNs;

            double cpuThrottle = cpuPressureRatio * throttleScale;

            this.readings.perCpuPressureRatio[i] = ewma(this.readings.perCpuPressureRatio[i],
                    Math.max(0.0, cpuPressureRatio));
            this.readings.perCpuThrottleRatio[i] = ewma(this.readings.perCpuThrottleRatio[i],
                    Math.max(0.0, cpuThrottle));
        }

        int totalCpus = snapshot.totalCpus();
        for (int i = effective.nextClearBit(0); i >= 0 && i < totalCpus;
                i = effective.nextClearBit(i + 1)) {
            this.readings.perCpuPressureRatio[i] = 0.0;
            this.readings.perCpuThrottleRatio[i] = 0.0;
        }

        this.globalEffectiveCpus = effective;
    }

    // ----- MEMORY -----

    private void updateMemory(SystemSnapshot snapshot) {
        long workingMemory = Math.max(0, snapshot.memoryUsage() - snapshot.inactiveFileMemory());

        if (snapshot.memoryLimit() > 0 && snapshot.memoryLimit() < 1_000_000_000_000_000L) {
            double workingMemoryUtil = (double) workingMemory / snapshot.memoryLimit();

            double availableCpus = snapshot.totalCpus();

            if (Double.isFinite(workingMemoryUtil)) {
                this.readings.memUsageRatio = ewma(this.readings.memUsageRatio,
                        clampDouble(workingMemoryUtil, 0.0, 1.0));

                // Density relative to the physical core count visible in the container
                this.readings.memPerCpuUsageBytes = (long) ((workingMemory * availableCpus)
                        / snapshot.memoryLimit());
            }
        }
    }

    // ----- IO -----

    private void updateDiskIO(SystemSnapshot snapshot) {
        long deltaTimeNs = snapshot.timeNs() - this.readings.lastWallClockNs;

        if (deltaTimeNs > 0) {
            double deltaTimeSec = deltaTimeNs * NS_TO_SEC;

            // Calculate raw Bytes Per Second
            long deltaBytes = (lastDiskIOBytes == 0) ? 0 : snapshot.diskIOBytes() - lastDiskIOBytes;
            double rawBps = deltaBytes / deltaTimeSec;

            this.readings.diskIOBytesPerSecond = ewma(this.readings.diskIOBytesPerSecond, rawBps);

            double currentPeak = Math.max(rawBps, this.readings.peakDiskIoBPS * 0.9999);
            double rawIoRatio =
                    (currentPeak > 0) ? this.readings.diskIOBytesPerSecond / currentPeak : 0.0;

            this.readings.peakDiskIoBPS = currentPeak;
            this.readings.diskIOPressure = clampDouble(rawIoRatio, 0.0, 1.0);
            this.lastDiskIOBytes = snapshot.diskIOBytes();
        }
    }

    private double ewma(double oldVal, double newVal) {
        double clampedNew = clampDouble(newVal, 0.0, 1.0);

        if (oldVal <= 0) {
            return clampedNew;
        }

        return (smoothingFactor * clampedNew) + (1 - smoothingFactor) * oldVal;
    }

    @FunctionalInterface
    public interface MonitorListener {

        void update(HardwareUtilization utilization);
    }

    private static final class Readings {

        public final double[] perCpuPressureRatio = new double[SystemInfo.getCpuCount()];
        public final double[] perCpuThrottleRatio = new double[SystemInfo.getCpuCount()];

        public long lastWallClockNs = System.nanoTime();

        public double quotaCpus = 0D;
        public double cpuUsageRatio = 0D;
        public double cpuThrottleRatio = 0D;

        public double memUsageRatio = 0D;
        public long memPerCpuUsageBytes = 0L;

        public double diskIOBytesPerSecond = 0D;
        public double diskIOPressure = 0D;
        public double peakDiskIoBPS = 1024D * 1024D;
    }
}
