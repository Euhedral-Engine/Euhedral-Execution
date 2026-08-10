package io.euhedral_execution.hardware_utils.linux;

import io.euhedral_execution.hardware_utils.SystemInfo;
import io.euhedral_execution.hardware_utils.common.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.common.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.internal.Constants;
import io.euhedral_execution.hardware_utils.internal.sampling.DetailedSystemSnapshotProvider;
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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Read-only Linux resource provider producing FastHardwareSample and SlowHardwareSample, as well
/// as implementing legacy SystemSnapshotProvider via getSnapshot(). Implements cgroup v1, v2,
/// hybrid, and bare-host discovery, bounded buffer channel reads, honest aggregate pressure
/// propagation, unlimited quota calculation, block-device filtering, and rate-limited logging.
public class LinuxResourceProvider implements DetailedSystemSnapshotProvider, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(Constants.getLoggerName(LinuxResourceProvider.class));

    private static final long LOG_RATE_LIMIT_NS = 60_000_000_000L; // 60s
    private static final VarHandle LOCK_STATE;

    static {
        try {
            LOCK_STATE = MethodHandles.lookup().findVarHandle(LinuxResourceProvider.class, "lockState", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Map<String, Long> lastLoggedNs = new ConcurrentHashMap<>();
    private final LinuxPaths paths;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(65_536);
    // Fast state
    private final BitSet effectiveCpus = new BitSet(SystemInfo.getCpuCount());
    private final long[] lastCpuIowaitJiffies = new long[SystemInfo.getCpuCount()];
    private final long[] lastCpuStealJiffies = new long[SystemInfo.getCpuCount()];
    private final double[] perCpuWaitStallNs = new double[SystemInfo.getCpuCount()];
    private long lastCgroupTotalStallNs = 0;

    @SuppressWarnings("unused")
    private volatile int lockState = 0;

    public LinuxResourceProvider() {
        this(new LinuxPaths());
    }

    public LinuxResourceProvider(LinuxPaths paths) {
        this.paths = paths;
    }

    public static boolean isFilteredBlockDevice(int major, String name) {
        if (major == 7 || name.startsWith("loop")) {
            return false;
        }
        if (major == 1 || name.startsWith("ram") || name.startsWith("zram")) {
            return false;
        }
        if (name.startsWith("sr")) {
            return false;
        }

        return name.startsWith("sd")
                || name.startsWith("nvme")
                || name.startsWith("vd")
                || name.startsWith("xvd")
                || name.startsWith("mmcblk")
                || name.startsWith("md")
                || name.startsWith("dm-");
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
            updateEffectiveCpus();
            double quotaCpus = calculateQuotaCpus();
            long cpuUsage = readCpuUsageNs();
            long cpuThrottle = readCpuThrottleNs();
            updateCpuPressure();
            long[] memStats = readMemoryStats();
            long diskIo = readFilteredDiskIoBytes();

            long[] memSnap =
                    new long[] {memStats[0] <= 0 ? Long.MAX_VALUE : memStats[0], memStats[1], 0L // inactive_file
                    };

            return SystemSnapshot.create(
                    now,
                    SystemInfo.getCpuCount(),
                    quotaCpus,
                    100_000L,
                    cpuUsage,
                    cpuThrottle,
                    UnmodifiableBitSet.wrap((BitSet) effectiveCpus.clone()),
                    perCpuWaitStallNs.clone(),
                    memSnap,
                    diskIo);
        } finally {
            releaseLock();
        }
    }

    @Override
    public FastHardwareSample sampleFast(long requestedAtNs) {
        acquireLock();
        try {
            updateEffectiveCpus();
            double quotaCpus = calculateQuotaCpus();

            long cpuUsageNs = readCpuUsageNs();
            long cpuThrottleNs = readCpuThrottleNs();

            updateCpuPressure();

            long[] memStats = readMemoryStats(); // [max, current]
            long memoryMax = memStats[0];
            long memoryCurrent = memStats[1];

            long diskIoBytes = readFilteredDiskIoBytes();
            long ioStallNs = readIoStallNs();

            int span = SystemInfo.getCpuCount();
            CpuFastSignals[] cpuSignals = new CpuFastSignals[span];
            for (int i = 0; i < span; i++) {
                cpuSignals[i] = new CpuFastSignals(
                        CounterSignal.valid((long) perCpuWaitStallNs[i], requestedAtNs),
                        CounterSignal.unsupported(requestedAtNs),
                        DoubleGaugeSignal.unsupported(requestedAtNs),
                        CounterSignal.unsupported(requestedAtNs),
                        CounterSignal.unsupported(requestedAtNs),
                        DoubleGaugeSignal.unsupported(requestedAtNs),
                        DoubleGaugeSignal.unsupported(requestedAtNs));
            }

            MemoryFastSignals memorySignals = new MemoryFastSignals(
                    memoryMax > 0
                            ? LongGaugeSignal.valid(memoryMax, requestedAtNs)
                            : LongGaugeSignal.unsupported(requestedAtNs),
                    LongGaugeSignal.unsupported(requestedAtNs),
                    LongGaugeSignal.valid(memoryCurrent, requestedAtNs),
                    LongGaugeSignal.unsupported(requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs));

            IoFastSignals ioSignals = new IoFastSignals(
                    CounterSignal.valid(diskIoBytes, requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs),
                    CounterSignal.valid(ioStallNs, requestedAtNs),
                    DoubleGaugeSignal.unsupported(requestedAtNs));

            return new FastHardwareSample(
                    requestedAtNs,
                    span,
                    UnmodifiableBitSet.wrap((BitSet) effectiveCpus.clone()),
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

    @Override
    public SlowHardwareSample sampleSlow(long requestedAtNs) {
        acquireLock();
        try {
            double currentFreqHz = readCpuFrequencyHz();
            double currentTempC = readThermalTemperatureC();

            boolean validFreq = !Double.isNaN(currentFreqHz) && currentFreqHz > 0;
            boolean validTemp = !Double.isNaN(currentTempC) && currentTempC > 0;

            int span = SystemInfo.getCpuCount();
            CpuSlowSignals[] cpuSlow = new CpuSlowSignals[span];
            for (int i = 0; i < span; i++) {
                cpuSlow[i] = new CpuSlowSignals(
                        DoubleGaugeSignal.unsupported(requestedAtNs),
                        DoubleGaugeSignal.unsupported(requestedAtNs),
                        validFreq
                                ? LongGaugeSignal.valid((long) currentFreqHz, requestedAtNs)
                                : LongGaugeSignal.unsupported(requestedAtNs),
                        LongGaugeSignal.unsupported(requestedAtNs),
                        new ThermalSignal(ThermalSeverity.NOMINAL, requestedAtNs, SignalValidity.UNSUPPORTED),
                        new BooleanSignal(false, requestedAtNs, SignalValidity.UNSUPPORTED));
            }

            SystemSlowSignals systemSlow = new SystemSlowSignals(
                    DoubleGaugeSignal.unsupported(requestedAtNs),
                    DoubleGaugeSignal.unsupported(requestedAtNs),
                    new ThermalSignal(
                            ThermalSeverity.NOMINAL,
                            requestedAtNs,
                            validTemp ? SignalValidity.VALID : SignalValidity.UNSUPPORTED),
                    new BooleanSignal(false, requestedAtNs, SignalValidity.UNSUPPORTED));

            return new SlowHardwareSample(requestedAtNs, span, cpuSlow, systemSlow);
        } finally {
            releaseLock();
        }
    }

    private void updateEffectiveCpus() {
        effectiveCpus.clear();
        Path cpusetFile = null;
        if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V2 || paths.getMode() == LinuxPaths.CgroupMode.HYBRID) {
            cpusetFile = paths.resolveV2Path("cpuset.cpus.effective");
        } else if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V1) {
            cpusetFile = paths.resolveV1Path("cpuset", "cpuset.cpus");
            if (cpusetFile == null) {
                cpusetFile = paths.resolveV1Path("cpu", "cpuset.cpus");
            }
        }

        if (cpusetFile != null) {
            String content = readFileBounded(cpusetFile);
            if (content != null && !content.isBlank()) {
                parseCpusetRange(content.trim(), effectiveCpus);
            }
        }

        if (effectiveCpus.isEmpty()) {
            effectiveCpus.set(0, SystemInfo.getCpuCount());
        }
    }

    private void parseCpusetRange(String cpusetStr, BitSet bitSet) {
        try {
            String[] parts = cpusetStr.split(",");
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty()) {
                    continue;
                }
                if (part.contains("-")) {
                    String[] range = part.split("-");
                    int start = Integer.parseInt(range[0]);
                    int end = Integer.parseInt(range[1]);
                    bitSet.set(start, Math.min(end + 1, SystemInfo.getCpuCount()));
                } else {
                    int cpu = Integer.parseInt(part);
                    if (cpu < SystemInfo.getCpuCount()) {
                        bitSet.set(cpu);
                    }
                }
            }
        } catch (Exception e) {
            logRateLimited(cpusetStr, "Failed to parse cpuset range: " + e.getMessage());
            bitSet.set(0, SystemInfo.getCpuCount());
        }
    }

    private double calculateQuotaCpus() {
        if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V2 || paths.getMode() == LinuxPaths.CgroupMode.HYBRID) {
            Path cpuMax = paths.resolveV2Path("cpu.max");
            if (cpuMax != null) {
                String content = readFileBounded(cpuMax);
                if (content != null) {
                    String[] parts = content.trim().split("\\s+");
                    if (parts.length >= 1 && !"max".equals(parts[0])) {
                        try {
                            long quota = Long.parseLong(parts[0]);
                            long period = parts.length >= 2 ? Long.parseLong(parts[1]) : 100_000L;
                            if (quota > 0 && period > 0) {
                                return (double) quota / period;
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } else if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V1) {
            Path quotaFile = paths.resolveV1Path("cpu", "cpu.cfs_quota_us");
            Path periodFile = paths.resolveV1Path("cpu", "cpu.cfs_period_us");
            if (quotaFile != null) {
                String qContent = readFileBounded(quotaFile);
                if (qContent != null) {
                    try {
                        long quota = Long.parseLong(qContent.trim());
                        if (quota > 0) {
                            long period = 100_000L;
                            if (periodFile != null) {
                                String pContent = readFileBounded(periodFile);
                                if (pContent != null) {
                                    period = Long.parseLong(pContent.trim());
                                }
                            }
                            return (double) quota / period;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return effectiveCpus.cardinality();
    }

    private long readCpuUsageNs() {
        if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V2 || paths.getMode() == LinuxPaths.CgroupMode.HYBRID) {
            Path cpuStat = paths.resolveV2Path("cpu.stat");
            if (cpuStat != null) {
                String content = readFileBounded(cpuStat);
                if (content != null) {
                    for (String line : content.split("\n")) {
                        if (line.startsWith("usage_usec ")) {
                            try {
                                return Long.parseLong(line.split("\\s+")[1]) * 1000L;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        } else if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V1) {
            Path cpuacct = paths.resolveV1Path("cpuacct", "cpuacct.usage");
            if (cpuacct != null) {
                String content = readFileBounded(cpuacct);
                if (content != null) {
                    try {
                        return Long.parseLong(content.trim());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return 0L;
    }

    private long readCpuThrottleNs() {
        if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V2 || paths.getMode() == LinuxPaths.CgroupMode.HYBRID) {
            Path cpuStat = paths.resolveV2Path("cpu.stat");
            if (cpuStat != null) {
                String content = readFileBounded(cpuStat);
                if (content != null) {
                    for (String line : content.split("\n")) {
                        if (line.startsWith("throttled_usec ")) {
                            try {
                                return Long.parseLong(line.split("\\s+")[1]) * 1000L;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        } else if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V1) {
            Path statFile = paths.resolveV1Path("cpu", "cpu.stat");
            if (statFile != null) {
                String content = readFileBounded(statFile);
                if (content != null) {
                    for (String line : content.split("\n")) {
                        if (line.startsWith("throttled_time ")) {
                            try {
                                return Long.parseLong(line.split("\\s+")[1]);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        }
        return 0L;
    }

    private void updateCpuPressure() {
        if (paths.getMode() == LinuxPaths.CgroupMode.BARE_HOST) {
            String procStat = readFileBounded(LinuxPaths.PROC_STAT);
            if (procStat != null) {
                for (String line : procStat.split("\n")) {
                    if (line.startsWith("cpu") && line.length() > 3 && Character.isDigit(line.charAt(3))) {
                        String[] parts = line.split("\\s+");
                        int cpuId = Integer.parseInt(parts[0].substring(3));
                        if (cpuId < SystemInfo.getCpuCount()) {
                            long iowait = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
                            long steal = parts.length > 8 ? Long.parseLong(parts[8]) : 0;

                            long deltaIowait = Math.max(0, iowait - lastCpuIowaitJiffies[cpuId]);
                            long deltaSteal = Math.max(0, steal - lastCpuStealJiffies[cpuId]);

                            lastCpuIowaitJiffies[cpuId] = iowait;
                            lastCpuStealJiffies[cpuId] = steal;

                            perCpuWaitStallNs[cpuId] = (deltaIowait + deltaSteal) * 10_000_000.0;
                        }
                    }
                }
            }
        } else {
            // Cgroup mode (v1/v2/hybrid): Aggregate PSI propagation
            Path cpuPressure = paths.resolveV2Path("cpu.pressure");
            long currentTotalStallNs = 0;
            if (cpuPressure != null) {
                String content = readFileBounded(cpuPressure);
                if (content != null) {
                    for (String line : content.split("\n")) {
                        if (line.startsWith("some ")) {
                            int totalIdx = line.indexOf("total=");
                            if (totalIdx != -1) {
                                try {
                                    String totalStr =
                                            line.substring(totalIdx + 6).split("\\s+")[0];
                                    currentTotalStallNs = Long.parseLong(totalStr) * 1000L;
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                }
            }
            long deltaStall = Math.max(0, currentTotalStallNs - lastCgroupTotalStallNs);
            lastCgroupTotalStallNs = currentTotalStallNs;

            int numEffective = Math.max(1, effectiveCpus.cardinality());
            double uniformStall = (double) deltaStall / numEffective;
            Arrays.fill(perCpuWaitStallNs, 0.0);
            for (int i = effectiveCpus.nextSetBit(0); i >= 0; i = effectiveCpus.nextSetBit(i + 1)) {
                if (i < SystemInfo.getCpuCount()) {
                    perCpuWaitStallNs[i] = uniformStall;
                }
            }
        }
    }

    private long[] readMemoryStats() {
        long max = -1;
        long current = 0;

        if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V2 || paths.getMode() == LinuxPaths.CgroupMode.HYBRID) {
            Path memMaxPath = paths.resolveV2Path("memory.max");
            if (memMaxPath != null) {
                String content = readFileBounded(memMaxPath);
                if (content != null && !"max".equals(content.trim())) {
                    try {
                        max = Long.parseLong(content.trim());
                    } catch (Exception ignored) {
                    }
                }
            }
            Path memCurrPath = paths.resolveV2Path("memory.current");
            if (memCurrPath != null) {
                String content = readFileBounded(memCurrPath);
                if (content != null) {
                    try {
                        current = Long.parseLong(content.trim());
                    } catch (Exception ignored) {
                    }
                }
            }
        } else if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V1) {
            Path memMaxPath = paths.resolveV1Path("memory", "memory.limit_in_bytes");
            if (memMaxPath != null) {
                String content = readFileBounded(memMaxPath);
                if (content != null) {
                    try {
                        long val = Long.parseLong(content.trim());
                        if (val < 9223372036854770000L) {
                            max = val;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            Path memCurrPath = paths.resolveV1Path("memory", "memory.usage_in_bytes");
            if (memCurrPath != null) {
                String content = readFileBounded(memCurrPath);
                if (content != null) {
                    try {
                        current = Long.parseLong(content.trim());
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        String meminfo = readFileBounded(LinuxPaths.PROC_MEMINFO);
        if (meminfo != null) {
            long memTotal = 0;
            for (String line : meminfo.split("\n")) {
                if (line.startsWith("MemTotal:")) {
                    memTotal = parseKbToBytes(line);
                    break;
                }
            }
            if (max <= 0) {
                max = memTotal;
            }
        }

        return new long[] {max, current};
    }

    private long parseKbToBytes(String line) {
        try {
            String[] parts = line.split("\\s+");
            return Long.parseLong(parts[1]) * 1024L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private long readFilteredDiskIoBytes() {
        String diskstats = readFileBounded(LinuxPaths.PROC_DISKSTATS);
        if (diskstats == null) {
            return 0L;
        }

        long totalBytes = 0;
        for (String line : diskstats.split("\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 14) {
                int major = Integer.parseInt(parts[0]);
                String devName = parts[2];

                if (isFilteredBlockDevice(major, devName)) {
                    long readSectors = Long.parseLong(parts[5]);
                    long writeSectors = Long.parseLong(parts[9]);
                    totalBytes += (readSectors + writeSectors) * 512L;
                }
            }
        }
        return totalBytes;
    }

    private long readIoStallNs() {
        Path ioPressure = paths.resolveV2Path("io.pressure");
        if (ioPressure != null) {
            String content = readFileBounded(ioPressure);
            if (content != null) {
                for (String line : content.split("\n")) {
                    if (line.startsWith("some ")) {
                        int totalIdx = line.indexOf("total=");
                        if (totalIdx != -1) {
                            try {
                                return Long.parseLong(
                                                line.substring(totalIdx + 6).split("\\s+")[0])
                                        * 1000L;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        }
        String diskstats = readFileBounded(LinuxPaths.PROC_DISKSTATS);
        if (diskstats != null) {
            long totalMs = 0;
            for (String line : diskstats.split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 14) {
                    int major = Integer.parseInt(parts[0]);
                    String devName = parts[2];
                    if (isFilteredBlockDevice(major, devName)) {
                        totalMs += Long.parseLong(parts[12]);
                    }
                }
            }
            return totalMs * 1_000_000L;
        }
        return 0L;
    }

    private double readCpuFrequencyHz() {
        Path curFreq = paths.CPU_INFO_BASE.resolve("cpu0/cpufreq/scaling_cur_freq");
        if (Files.exists(curFreq)) {
            String content = readFileBounded(curFreq);
            if (content != null) {
                try {
                    return Double.parseDouble(content.trim()) * 1000.0;
                } catch (Exception ignored) {
                }
            }
        }
        return Double.NaN;
    }

    private double readThermalTemperatureC() {
        try {
            if (Files.exists(LinuxPaths.THERMAL_BASE)) {
                try (var stream = Files.list(LinuxPaths.THERMAL_BASE)) {
                    for (Path zone : stream.filter(
                                    p -> p.getFileName().toString().startsWith("thermal_zone"))
                            .toList()) {
                        Path tempFile = zone.resolve("temp");
                        if (Files.exists(tempFile)) {
                            String content = readFileBounded(tempFile);
                            if (content != null) {
                                return Double.parseDouble(content.trim()) / 1000.0;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Double.NaN;
    }

    public String readFileBounded(Path path) {
        if (path == null || !Files.exists(path)) {
            return null;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
            long pos = 0;
            while (true) {
                buffer.clear();
                int bytesRead = channel.read(buffer, pos);
                if (bytesRead <= 0) {
                    break;
                }
                pos += bytesRead;
                buffer.flip();
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytesOut.write(chunk);
            }
            return bytesOut.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            logRateLimited(path.toString(), "Error reading file " + path + ": " + e.getMessage());
            return null;
        }
    }

    private void logRateLimited(String key, String message) {
        long now = System.nanoTime();
        Long last = lastLoggedNs.get(key);
        if (last == null || (now - last) >= LOG_RATE_LIMIT_NS) {
            LOGGER.warn(message);
            lastLoggedNs.put(key, now);
        }
    }

    @Override
    public void close() {}
}
