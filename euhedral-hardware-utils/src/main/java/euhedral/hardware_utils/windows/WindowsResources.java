package euhedral.hardware_utils.windows;

import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.internal.JNIClassLoader;
import euhedral.hardware_utils.common.OSName;
import euhedral.hardware_utils.common.SystemSnapshotProvider;
import euhedral.hardware_utils.common.SystemUtilization.SystemSnapshot;
import euhedral.hardware_utils.common.UnmodifiableBitSet;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WindowsResources implements SystemSnapshotProvider {

    public static final WindowsResources INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(WindowsResources.class);

    static {
        WindowsResources instance = null;
        if (OSName.isWindows()) {
            try {
                JNIClassLoader.load(WindowsResources.class);
                instance = new WindowsResources();

            } catch (Throwable t) {
                LOGGER.error("Failed to load windows_resources", t);
            }
        }
        INSTANCE = instance;
    }

    private final AtomicReference<SystemSnapshot> snapshot = new AtomicReference<>();

    private final AtomicBoolean wip = new AtomicBoolean(false);

    private final long[] buffer;
    private final double[] lastIdle;
    private final double[] currentIdle;
    private final double[] pressure;

    private long lastTime;

    public WindowsResources() {
        buffer = new long[Math.max(SystemInfo.getCpuCount(), 3)];
        lastIdle = new double[SystemInfo.getCpuCount()];
        currentIdle = new double[SystemInfo.getCpuCount()];
        pressure = new double[SystemInfo.getCpuCount()];
    }

    @Override
    public SystemSnapshot getSnapshot() {
        if(!this.wip.compareAndSet(false, true)) {
            while(this.wip.get()) {
                Thread.onSpinWait();
            }
            return this.snapshot.get();
        }

        SystemSnapshot snapshot = this.snapshot.get();
        try {
            long now = System.nanoTime();

            // CPU
            int availableCpus = Runtime.getRuntime().availableProcessors();

            long affinityMask = getAffinityMask();
            BitSet effectiveCpus = toBitSet(affinityMask);

            Arrays.fill(this.currentIdle, 0);
            getPerCpuLoad(this.currentIdle);
            computeCpuPressure(now);

            long period = 100_000L; // Windows has no period equivalent
            double quota = getCpuQuota();
            double quotaCpus = quota > 0 ? Math.min(quota, availableCpus) : availableCpus;

            Arrays.fill(this.buffer, 0);
            getCpuTimes(this.buffer);
            long cpuUsage = this.buffer[0];
            long cpuThrottle = this.buffer[1];

            long ioBytes = getIoBytes();

            Arrays.fill(this.buffer, 0);
            getMemorySnapshot(this.buffer);
            snapshot = SystemSnapshot.create(
                    now,
                    availableCpus,
                    quotaCpus,
                    period,
                    cpuUsage,
                    cpuThrottle,
                    UnmodifiableBitSet.wrap(effectiveCpus),
                    this.pressure,
                    this.buffer,
                    ioBytes
            );
        } catch (Throwable t) {
            LOGGER.error("Error generating SystemSnapshot.", t);
        } finally {
            this.snapshot.set(snapshot);
            this.wip.set(false);
        }
        return snapshot;
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

    private void computeCpuPressure(long now) {
        long dt = now - lastTime;

        for (int i = 0; i < this.currentIdle.length; i++) {
            double deltaIdle = this.currentIdle[i] - this.lastIdle[i];

            double busy = Math.max(0, 1.0 - (deltaIdle / dt));
            this.pressure[i] = Math.min(1.0, busy);
        }

        System.arraycopy(this.currentIdle, 0, this.lastIdle, 0, this.lastIdle.length);
        this.lastTime = now;
    }
}

