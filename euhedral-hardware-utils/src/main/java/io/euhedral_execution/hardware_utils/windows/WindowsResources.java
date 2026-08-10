package io.euhedral_execution.hardware_utils.windows;

import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.common.OSName;
import io.euhedral_execution.hardware_utils.common.SystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.JNIClassLoader;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.internal.sampling.enums.ThermalSeverity;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.BooleanSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.DoubleGaugeSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.primitives.LongGaugeSignal;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.samples.SlowHardwareSample;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuFastSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.CpuSlowSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.IoFastSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.MemoryFastSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.SystemSlowSignals;
import io.euhedral_execution.hardware_utils.internal.sampling.signals.ThermalSignal;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Read-only Windows resource provider producing FastHardwareSample and SlowHardwareSample, as well
/// as implementing SystemSnapshotProvider via getSnapshot(). Implements Job Object CPU quota
/// scaling, working set underflow protection, process CPU time conversion, idle cycle delta
/// normalization, and cumulative I/O bytes.
public final class WindowsResources implements SystemSnapshotProvider {

    public static final WindowsResources INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(WindowsResources.class));
    private static final VarHandle LOCK_STATE;

    static {
        JNIClassLoader.load();

        WindowsResources instance = null;
        if (OSName.isWindows()) {
            instance = new WindowsResources();
        }
        INSTANCE = instance;

        try {
            LOCK_STATE = MethodHandles.lookup().findVarHandle(WindowsResources.class, "lockState", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final AtomicReference<SystemSnapshot> snapshot = new AtomicReference<>();
    private final long[] buffer;
    private final double[] lastIdle;
    private final double[] currentIdle;
    private final double[] pressure;
    @SuppressWarnings("unused")
    private volatile int lockState = 0;
    private long lastTime;

    public WindowsResources() {
        int cpuCount = Math.max(1, SystemInfo.getCpuCount());
        buffer = new long[Math.max(cpuCount, 3)];
        lastIdle = new double[cpuCount];
        currentIdle = new double[cpuCount];
        pressure = new double[cpuCount];
    }

    public static native void getCpuTimes(long[] buffer);

    public static native double getCpuQuota();

    public static native long getAffinityMask();

    public static native void getPerCpuLoad(double[] buffer);

    public static native void getMemorySnapshot(long[] buffer);

    public static native long getIoBytes();

    private static BitSet toBitSet(long mask) {
        BitSet bs = new BitSet();
        while (mask != 0) {
            int bit = Long.numberOfTrailingZeros(mask);
            bs.set(bit);
            mask &= ~(1L << bit);
        }
        return bs;
    }

    private void acquireLock() {
        int backoff = 0;
        while (!LOCK_STATE.compareAndSet(this, 0, 1)) {
            if (backoff < 10) {
                Thread.onSpinWait();
            } else if (backoff < 100) {
                Thread.yield();
            } else {
                LockSupport.parkNanos(1_000L);
            }
            backoff++;
        }
    }

    private void releaseLock() {
        LOCK_STATE.setRelease(this, 0);
    }

    @Override
    public SystemSnapshot getSnapshot() {
        acquireLock();
        try {
            long now = System.nanoTime();

            int availableCpus = SystemInfo.getCpuCount();
            long affinityMask = getAffinityMask();
            BitSet effectiveCpus = toBitSet(affinityMask);

            Arrays.fill(this.currentIdle, 0.0);
            getPerCpuLoad(this.currentIdle);
            computeCpuPressure(now);

            long period = 100_000L; // Windows quota period standard
            double quota = getCpuQuota();
            double quotaCpus = quota > 0.0 ? quota * availableCpus : availableCpus;

            Arrays.fill(this.buffer, 0L);
            getCpuTimes(this.buffer);
            long cpuUsage = this.buffer[0];
            long cpuThrottle = this.buffer[1];

            long ioBytes = getIoBytes();

            Arrays.fill(this.buffer, 0L);
            getMemorySnapshot(this.buffer);
            SystemSnapshot snap = SystemSnapshot.create(
                    now,
                    availableCpus,
                    quotaCpus,
                    period,
                    cpuUsage,
                    cpuThrottle,
                    UnmodifiableBitSet.wrap(effectiveCpus),
                    this.pressure.clone(),
                    this.buffer.clone(),
                    ioBytes);
            this.snapshot.set(snap);
            return snap;
        } catch (Exception e) {
            LOGGER.error("Error generating SystemSnapshot.", e);
            return this.snapshot.get();
        } finally {
            releaseLock();
        }
    }

    public FastHardwareSample sampleFast(long requestedAtNs) {
        acquireLock();
        try {
            int span = SystemInfo.getCpuCount();
            long affinityMask = getAffinityMask();
            BitSet effectiveCpus = toBitSet(affinityMask);

            double quota = getCpuQuota();
            double quotaCpus = quota > 0.0 ? quota * span : span;

            Arrays.fill(this.buffer, 0L);
            getCpuTimes(this.buffer);
            long cpuUsageNs = this.buffer[0];
            long cpuThrottleNs = this.buffer[1];

            Arrays.fill(this.currentIdle, 0.0);
            getPerCpuLoad(this.currentIdle);
            computeCpuPressure(requestedAtNs);

            Arrays.fill(this.buffer, 0L);
            getMemorySnapshot(this.buffer);
            long memoryLimit = this.buffer[0];
            long workingSet = this.buffer[1];
            long sharedMemory = Math.max(0L, this.buffer[2]);

            long ioBytes = getIoBytes();

            CpuFastSignals[] cpuSignals = new CpuFastSignals[span];
            for (int i = 0; i < span; i++) {
                cpuSignals[i] = new CpuFastSignals(
                        CounterSignal.unsupported(requestedAtNs),
                        CounterSignal.unsupported(requestedAtNs),
                        DoubleGaugeSignal.valid(this.pressure[i], requestedAtNs),
                        CounterSignal.unsupported(requestedAtNs),
                        CounterSignal.unsupported(requestedAtNs),
                        DoubleGaugeSignal.unsupported(requestedAtNs),
                        DoubleGaugeSignal.unsupported(requestedAtNs));
            }

            MemoryFastSignals memorySignals = new MemoryFastSignals(
                    memoryLimit > 0
                            ? LongGaugeSignal.valid(memoryLimit, requestedAtNs)
                            : LongGaugeSignal.unsupported(requestedAtNs),
                    LongGaugeSignal.unsupported(requestedAtNs),
                    LongGaugeSignal.valid(workingSet, requestedAtNs),
                    LongGaugeSignal.valid(sharedMemory, requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs));

            IoFastSignals ioSignals = new IoFastSignals(
                    CounterSignal.valid(ioBytes, requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs),
                    DoubleGaugeSignal.unsupported(requestedAtNs));

            return new FastHardwareSample(
                    requestedAtNs,
                    span,
                    UnmodifiableBitSet.wrap(effectiveCpus),
                    LongGaugeSignal.valid((long) quotaCpus, requestedAtNs),
                    LongGaugeSignal.valid(100_000L, requestedAtNs),
                    CounterSignal.valid(cpuUsageNs, requestedAtNs),
                    CounterSignal.valid(cpuThrottleNs, requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs),
                    DoubleGaugeSignal.unsupported(requestedAtNs),
                    cpuSignals,
                    memorySignals,
                    ioSignals);
        } finally {
            releaseLock();
        }
    }

    public SlowHardwareSample sampleSlow(long requestedAtNs) {
        acquireLock();
        try {
            int span = SystemInfo.getCpuCount();
            CpuSlowSignals[] cpuSlow = new CpuSlowSignals[span];
            for (int i = 0; i < span; i++) {
                cpuSlow[i] = new CpuSlowSignals(
                        DoubleGaugeSignal.unsupported(requestedAtNs),
                        DoubleGaugeSignal.unsupported(requestedAtNs),
                        LongGaugeSignal.unsupported(requestedAtNs),
                        LongGaugeSignal.unsupported(requestedAtNs),
                        new ThermalSignal(ThermalSeverity.NOMINAL, requestedAtNs, SignalValidity.UNSUPPORTED),
                        new BooleanSignal(false, requestedAtNs, SignalValidity.UNSUPPORTED));
            }

            SystemSlowSignals systemSlow = new SystemSlowSignals(
                    DoubleGaugeSignal.unsupported(requestedAtNs),
                    DoubleGaugeSignal.unsupported(requestedAtNs),
                    new ThermalSignal(ThermalSeverity.NOMINAL, requestedAtNs, SignalValidity.UNSUPPORTED),
                    new BooleanSignal(false, requestedAtNs, SignalValidity.UNSUPPORTED));

            return new SlowHardwareSample(requestedAtNs, span, cpuSlow, systemSlow);
        } finally {
            releaseLock();
        }
    }

    private void computeCpuPressure(long now) {
        long dt = now - lastTime;

        if (dt > 0 && lastTime > 0) {
            for (int i = 0; i < this.currentIdle.length; i++) {
                double deltaIdle = this.currentIdle[i] - this.lastIdle[i];
                if (deltaIdle < 0) {
                    deltaIdle = 0;
                }
                double busy = Math.max(0.0, 1.0 - (deltaIdle / dt));
                this.pressure[i] = Math.min(1.0, busy);
            }
        } else {
            Arrays.fill(this.pressure, 0.0);
        }

        System.arraycopy(this.currentIdle, 0, this.lastIdle, 0, this.lastIdle.length);
        this.lastTime = now;
    }
}
