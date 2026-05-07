package euhedral.hardware_utils.macOS;

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

public final class OSXResources implements SystemSnapshotProvider {

    public static final OSXResources INSTANCE;
    private static final Logger LOGGER = LoggerFactory.getLogger(OSXResources.class);

    static {
        OSXResources instance = null;
        if (OSName.isMacOS()) {
            try {
                JNIClassLoader.load(OSXResources.class);
                instance = new OSXResources();
            } catch (Throwable t) {
                LOGGER.error("Failed to load the mac_resources library", t);
            }
        }

        INSTANCE = instance;
    }

    private final AtomicBoolean wip = new AtomicBoolean(false);
    private final AtomicReference<SystemSnapshot> snapshot = new AtomicReference<>();
    private final UnmodifiableBitSet effectiveCpus;
    private final long[] lastCpuTimes = new long[2];
    private long lastTimeNs = -1;

    public OSXResources() throws Throwable {
        int available = Runtime.getRuntime().availableProcessors();
        BitSet set = new BitSet(available);
        set.set(0, available);
        this.effectiveCpus = UnmodifiableBitSet.wrap(set);
    }

    public SystemSnapshot getSnapshot() {
        if (!this.wip.compareAndSet(false, true)) {
            while (this.wip.get()) {
                Thread.onSpinWait();
            }
            return this.snapshot.get();
        }

        SystemSnapshot snapshot = this.snapshot.get();
        try {
            long now = System.nanoTime();

            long[] cpuTimes = getCpuTimes();
            long totalCpuTime = cpuTimes.length > 0 ? cpuTimes[0] : 0;

            long cpuUsageDelta = 0;
            long dtCpu = now - lastTimeNs;

            if (lastTimeNs > 0 && dtCpu > 0) {
                long prev = lastCpuTimes[0];
                cpuUsageDelta = Math.max(0, totalCpuTime - prev);
            }

            lastCpuTimes[0] = totalCpuTime;
            lastTimeNs = now;

            long cpuThrottle = 0;

            int availableCpus = Runtime.getRuntime().availableProcessors();

            double systemLoad = getSystemCpuLoad();

            double processUtil = (double) cpuUsageDelta / (dtCpu * availableCpus);
            processUtil = Math.min(1.0, Math.max(0.0, processUtil));
            double pressure = systemLoad * (1.0 - processUtil);

            double[] pressurePerCpu = new double[availableCpus];
            Arrays.fill(pressurePerCpu, pressure);

            long ioBytes = getIoBytes();

            snapshot = SystemSnapshot.create(
                    now,
                    availableCpus,
                    availableCpus, // MacOS has no quota cpus
                    1L, // MacOS has no cgroup period equivalent
                    cpuUsageDelta,
                    cpuThrottle,
                    effectiveCpus,
                    pressurePerCpu,
                    getMemorySnapshot(),
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

    public static native long[] getCpuTimes();

    public static native double getSystemCpuLoad();

    public static native long[] getMemorySnapshot();

    public static native long getIoBytes();
}
