package euhedral.io.resource_monitoring.providers;

import euhedral.io.resource_monitoring.SystemUtilization.SystemSnapshot;

import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.BitSet;

public class WindowsResources implements ResourceProvider {

    private static final boolean AVAILABLE;

    static {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            String arch = System.getProperty("os.arch");
            String filePath;
            String suffix;
            if (arch.startsWith("aarch64") || arch.contains("arm64")) {
                filePath = "/monitoring/bin/windows_resources_arm64.dll";
                suffix = "windows_resources_arm64.dll";
            } else {
                filePath = "/monitoring/bin/windows_resources_x64.dll";
                suffix = "windows_resources_x64.dll";
            }

            boolean exception = false;
            try (var in = WindowsResources.class.getResourceAsStream(filePath)) {
                if (in == null) {
                    throw new RuntimeException(filePath + " not found in resources");
                }

                var tempFile = Files.createTempFile("monitoring_", suffix);
                tempFile.toFile().deleteOnExit();

                Files.copy(in, tempFile,
                        StandardCopyOption.REPLACE_EXISTING);

                System.load(tempFile.toAbsolutePath().toString());
            } catch (Throwable ignored) {
                exception = true;
            }
            AVAILABLE = !exception;
        } else {
            AVAILABLE = false;
        }
    }

    private double[] lastIdle;
    private long lastTime;

    public WindowsResources() {
        if (!AVAILABLE) {
            throw new RuntimeException("WindowsResources is not available.");
        }
    }

    @Override
    public SystemSnapshot getSnapshot() {
        if (!AVAILABLE) {
            throw new RuntimeException("Windows resource monitoring is not available");
        }

        long now = System.nanoTime();

        // CPU
        int availableCpus = Runtime.getRuntime().availableProcessors();

        long affinityMask = getAffinityMask();
        BitSet effectiveCpus = toBitSet(affinityMask);

        double[] rawIdle = getPerCpuLoad();
        double[] pressurePerCpu = computeCpuPressure(rawIdle, now);

        long period = 100_000L; // Windows has no period equivalent
        double quota = getCpuQuota();
        double quotaCpus = quota > 0 ? Math.min(quota, availableCpus) : availableCpus;

        long[] cpuTimes = getCpuTimes();
        long cpuUsage = cpuTimes.length > 0 ? cpuTimes[0] : 0;
        long cpuThrottle = cpuTimes.length > 1 ? cpuTimes[1] : 0;

        long ioBytes = getIoBytes();

        return SystemSnapshot.create(
                now,
                availableCpus,
                quotaCpus,
                period,
                cpuUsage,
                cpuThrottle,
                getCoreTypeMask(true), getCoreTypeMask(false),
                effectiveCpus,
                pressurePerCpu,
                getMemorySnapshot(),
                ioBytes
        );
    }

    public static native long[] getCpuTimes();

    public static native double getCpuQuota();

    public static native long getAffinityMask();

    public static native double[] getPerCpuLoad();

    public static native long[] getMemorySnapshot();

    public static native long getIoBytes();

    public static native long[][] getCoreTypeMask(boolean getPCores);

    private static BitSet toBitSet(long mask) {
        BitSet bs = new BitSet();
        while (mask != 0) {
            int bit = Long.numberOfTrailingZeros(mask);
            bs.set(bit);
            mask &= ~(1L << bit);
        }
        return bs;
    }

    private double[] computeCpuPressure(double[] currentIdle, long now) {
        if (lastIdle == null) {
            lastIdle = currentIdle;
            lastTime = now;
            return new double[currentIdle.length];
        }

        double[] pressure = new double[currentIdle.length];
        long dt = now - lastTime;

        for (int i = 0; i < currentIdle.length; i++) {
            double deltaIdle = currentIdle[i] - lastIdle[i];

            double busy = Math.max(0, 1.0 - (deltaIdle / dt));
            pressure[i] = Math.min(1.0, busy);
        }

        lastIdle = currentIdle;
        lastTime = now;

        return pressure;
    }
}
