package euhedral.io.resource_monitoring.providers;

import static euhedral.io.utils.MathFunctions.clampDouble;

import euhedral.io.resource_monitoring.SystemUtilization.SystemSnapshot;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.BitSet;

public class MacOSResources implements ResourceProvider {

    private static final boolean AVAILABLE;

    static {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("mac")) {
            String arch = System.getProperty("os.arch");
            String filePath;
            String suffix;
            if (arch.startsWith("aarch64") || arch.contains("arm64")) {
                filePath = "/monitoring/mac_resources_arm64.dylib";
                suffix = "mac_resources_arm64.dylib";
            } else {
                filePath = "/monitoring/mac_resources_x64.dylib";
                suffix = "mac_resources_x64.dylib";
            }

            boolean exception = false;
            try (var in = MacOSResources.class.getResourceAsStream(filePath)) {
                if (in == null) {
                    throw new RuntimeException(filePath + " not found in resources");
                }

                var tempFile = Files.createTempFile("monitoring_",
                        suffix);
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

    private final long[] lastCpuTimes = new long[2];
    private long lastTimeNs = -1;

    public MacOSResources() {
        if (!AVAILABLE) {
            throw new RuntimeException("MacOsResources is not available.");
        }
    }

    public SystemSnapshot getSnapshot() {

        long now = System.nanoTime();

        // CPU

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
        processUtil = clampDouble(processUtil, 0.0, 1.0);
        double pressure = systemLoad * (1.0 - processUtil);

        double[] pressurePerCpu = new double[availableCpus];
        Arrays.fill(pressurePerCpu, pressure);

        BitSet effectiveCpus = new BitSet(availableCpus);
        effectiveCpus.set(0, availableCpus);

        // Memory

        long[] mem = getMemorySnapshot();

        long memoryLimit = mem.length > 0 ? mem[0] : 0;
        long memoryUsage = mem.length > 1 ? mem[1] : 0;
        long inactiveFile = mem.length > 2 ? mem[2] : 0;

        long ioBytes = getIoBytes();

        return new SystemSnapshot(
                now,
                availableCpus,
                availableCpus, // macOS has no quota cpus
                100_000L, // macOS has no cgroup period; kept for compatibility
                cpuUsageDelta,
                cpuThrottle,
                effectiveCpus,
                pressurePerCpu,
                memoryLimit,
                memoryUsage,
                inactiveFile,
                ioBytes
        );
    }

    public static native long[] getCpuTimes();

    public static native double getSystemCpuLoad();

    public static native long[] getMemorySnapshot();

    public static native long getIoBytes();
}
