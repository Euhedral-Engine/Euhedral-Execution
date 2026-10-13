package io.euhedral_execution.hardware_utils.linux;

import io.euhedral_execution.hardware_utils.monitor.SystemUtilization.SystemSnapshot;
import io.euhedral_execution.hardware_utils.monitor.sampling.DetailedSystemSnapshotProvider;
import io.euhedral_execution.hardware_utils.monitor.sampling.enums.SignalValidity;
import io.euhedral_execution.hardware_utils.monitor.sampling.enums.ThermalSeverity;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.BooleanSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.CounterSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.DoubleGaugeSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.primitives.LongGaugeSignal;
import io.euhedral_execution.hardware_utils.monitor.sampling.samples.FastHardwareSample;
import io.euhedral_execution.hardware_utils.monitor.sampling.samples.SlowHardwareSample;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.CpuFastSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.CpuSlowSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.IoFastSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.MemoryFastSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.SystemSlowSignals;
import io.euhedral_execution.hardware_utils.monitor.sampling.signals.ThermalSignal;
import io.euhedral_execution.hardware_utils.topology.SystemInfo;
import io.euhedral_execution.hardware_utils.util.UnmodifiableBitSet;
import io.euhedral_execution.hardware_utils.util.internal.Constants;
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
import org.jspecify.annotations.Nullable;
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
    private final Path diskstatsPath;
    private final @Nullable Path ioPressureOverride;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(65_536);
    // Fast state
    private final BitSet effectiveCpus = new BitSet(SystemInfo.getCpuCount());
    private final long[] lastCpuIowaitJiffies = new long[SystemInfo.getCpuCount()];
    private final long[] lastCpuStealJiffies = new long[SystemInfo.getCpuCount()];
    private final double[] perCpuWaitStallNs = new double[SystemInfo.getCpuCount()];
    private long lastCgroupTotalStallNs = 0;

    // Read outcomes. A source that does not exist on this host is ABSENT (UNSUPPORTED signal); one
    // that exists or existed but could not be read, parsed or bounded is FAILED (TRANSIENT_FAILURE).
    private static final int OK = 0;
    private static final int ABSENT = 1;
    private static final int FAILED = 2;
    private static final int MAX_FILE_BYTES = 1 << 20;
    private static final long DEFAULT_PERIOD_MICROS = 100_000L;

    private static final int SRC_CPU_MAX = 1;
    private static final int SRC_CFS_QUOTA = 1 << 1;
    private static final int SRC_CFS_PERIOD = 1 << 2;
    private static final int SRC_CPU_STAT = 1 << 3;
    private static final int SRC_CPUACCT = 1 << 4;
    private static final int SRC_V1_CPU_STAT = 1 << 5;
    private static final int SRC_PROC_STAT = 1 << 6;
    private static final int SRC_CPU_PRESSURE = 1 << 7;
    private static final int SRC_MEM_MAX = 1 << 8;
    private static final int SRC_MEM_CUR = 1 << 9;
    private static final int SRC_MEMINFO = 1 << 10;
    private static final int SRC_DISKSTATS = 1 << 11;
    private static final int SRC_IO_PRESSURE = 1 << 12;
    // Per-key seen bits: a file can be present while a key is legitimately missing (cpu.stat has no
    // throttled_usec when the cpu controller is not enabled for the cgroup).
    private static final int KEY_USAGE = 1 << 13;
    private static final int KEY_THROTTLE = 1 << 14;

    // I/O stall source, chosen at first sight and never switched: the two counters have unrelated
    // baselines, so mixing them mid-stream would corrupt deltas.
    private static final int IO_SRC_UNCHOSEN = 0;
    private static final int IO_SRC_PRESSURE = 1;
    private static final int IO_SRC_DISKSTATS = 2;
    private int ioStallSource = IO_SRC_UNCHOSEN;

    // Per-provider scratch and result slots, all guarded by lockState; nothing here allocates per tick.
    private byte[] scratch = new byte[16_384];
    private final long[] statOut = new long[3];
    private final long[] diskOut = new long[6];
    private int seenMask;
    private int readStatus;
    private double quotaCpusValue;
    private long quotaPeriodMicros = DEFAULT_PERIOD_MICROS;
    private int quotaStatus;
    private int periodStatus;
    private long cpuUsageNs;
    private int usageStatus;
    private long cpuThrottleNs;
    private int throttleStatus;
    private int pressureStatus;
    private long memMax;
    private int memMaxStatus;
    private long memCurrent;
    private int memCurStatus;
    private long diskIoBytes;
    private int diskStatus;
    private long ioStallNs;
    private int ioStallStatus;
    private volatile DoubleGaugeSignal quotaExact = DoubleGaugeSignal.unsupported(0L);

    @SuppressWarnings("unused")
    private volatile int lockState = 0;

    public LinuxResourceProvider() {
        this(new LinuxPaths());
    }

    public LinuxResourceProvider(LinuxPaths paths) {
        this(paths, LinuxPaths.PROC_DISKSTATS, null);
    }

    /// Test seam: fixtures stand in for `/proc/diskstats` and for the cgroup `io.pressure` file (which
    /// `LinuxPaths.resolveV2Path` would otherwise fall back to the host's real cgroup root for).
    LinuxResourceProvider(LinuxPaths paths, Path diskstatsPath, @Nullable Path ioPressureOverride) {
        this.paths = paths;
        this.diskstatsPath = diskstatsPath;
        this.ioPressureOverride = ioPressureOverride;
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

    /// Exact (fractional) CPU quota as of the most recent {@link #sampleFast(long)} or
    /// {@link #getSnapshot()} (this provider updates it from both; {@code WindowsResources} updates
    /// it from {@code sampleFast} only). It holds the latest sampled value, not a value tied to one
    /// sample: callers should compare its {@code observedAtNs} with the sample's timestamp. {@code
    /// FastHardwareSample#quotaCapacityCpus()} is a whole-CPU long
    /// and truncates toward zero (a 0.5 CPU quota reads 0); this signal carries the unrounded value.
    /// It is UNSUPPORTED until the first sample, and TRANSIENT_FAILURE when the quota could not be
    /// read or parsed.
    public DoubleGaugeSignal quotaCapacityCpusExact() {
        return quotaExact;
    }

    @Override
    public SystemSnapshot getSnapshot() {
        acquireLock();
        try {
            long now = System.nanoTime();
            updateEffectiveCpus();
            readQuota(now);
            readCpuUsageNs();
            readCpuThrottleNs();
            updateCpuPressure();
            readMemoryStats();
            readFilteredDiskIoBytes();

            // The legacy snapshot has no validity channel: a value that is not OK reads as 0, except an
            // unreadable quota (effective-CPU count) and an unreadable memory limit (Long.MAX_VALUE,
            // i.e. unlimited), which match the pre-existing legacy shape.
            double quotaCpus = quotaStatus == OK ? quotaCpusValue : effectiveCpus.cardinality();
            long periodMicros = periodStatus == OK ? quotaPeriodMicros : DEFAULT_PERIOD_MICROS;
            long[] memSnap = new long[] {
                !(memMaxStatus == OK) || memMax <= 0 ? Long.MAX_VALUE : memMax, memCurStatus == OK ? memCurrent : 0L, 0L
            };

            return SystemSnapshot.create(
                    now,
                    SystemInfo.getCpuCount(),
                    quotaCpus,
                    periodMicros,
                    usageStatus == OK ? cpuUsageNs : 0L,
                    throttleStatus == OK ? cpuThrottleNs : 0L,
                    UnmodifiableBitSet.wrap((BitSet) effectiveCpus.clone()),
                    perCpuWaitStallNs.clone(),
                    memSnap,
                    diskStatus == OK ? diskIoBytes : 0L);
        } finally {
            releaseLock();
        }
    }

    @Override
    public FastHardwareSample sampleFast(long requestedAtNs) {
        acquireLock();
        try {
            updateEffectiveCpus();
            readQuota(requestedAtNs);

            readCpuUsageNs();
            readCpuThrottleNs();

            updateCpuPressure();

            readMemoryStats();
            readFilteredDiskIoBytes();
            readIoStallNs();

            int span = SystemInfo.getCpuCount();
            CpuFastSignals[] cpuSignals = new CpuFastSignals[span];
            for (int i = 0; i < span; i++) {
                cpuSignals[i] = new CpuFastSignals(
                        counter(pressureStatus, (long) perCpuWaitStallNs[i], requestedAtNs),
                        CounterSignal.unsupported(requestedAtNs),
                        DoubleGaugeSignal.unsupported(requestedAtNs),
                        CounterSignal.unsupported(requestedAtNs),
                        CounterSignal.unsupported(requestedAtNs),
                        DoubleGaugeSignal.unsupported(requestedAtNs),
                        DoubleGaugeSignal.unsupported(requestedAtNs));
            }

            LongGaugeSignal hardLimit;
            if (memMaxStatus == FAILED) {
                hardLimit = LongGaugeSignal.transientFailure(requestedAtNs);
            } else if (memMaxStatus == OK && memMax > 0) {
                hardLimit = LongGaugeSignal.valid(memMax, requestedAtNs);
            } else {
                hardLimit = LongGaugeSignal.unsupported(requestedAtNs);
            }
            MemoryFastSignals memorySignals = new MemoryFastSignals(
                    hardLimit,
                    LongGaugeSignal.unsupported(requestedAtNs),
                    gauge(memCurStatus, memCurrent, requestedAtNs),
                    LongGaugeSignal.unsupported(requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs));

            IoFastSignals ioSignals = new IoFastSignals(
                    counter(diskStatus, diskIoBytes, requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs),
                    CounterSignal.unsupported(requestedAtNs),
                    counter(ioStallStatus, ioStallNs, requestedAtNs),
                    DoubleGaugeSignal.unsupported(requestedAtNs));

            LongGaugeSignal quotaSignal;
            LongGaugeSignal periodSignal;
            if (quotaStatus == OK) {
                // Truncating cast kept deliberately: this is the pre-existing long semantics.
                // The unrounded value is exposed via quotaCapacityCpusExact().
                quotaSignal = LongGaugeSignal.valid((long) quotaCpusValue, requestedAtNs);
            } else {
                quotaSignal = LongGaugeSignal.transientFailure(requestedAtNs);
            }
            if (periodStatus == OK) {
                periodSignal = LongGaugeSignal.valid(quotaPeriodMicros * 1000L, requestedAtNs);
            } else {
                periodSignal = LongGaugeSignal.transientFailure(requestedAtNs);
            }

            return new FastHardwareSample(
                    requestedAtNs,
                    span,
                    UnmodifiableBitSet.wrap((BitSet) effectiveCpus.clone()),
                    quotaSignal,
                    periodSignal,
                    counter(usageStatus, cpuUsageNs, requestedAtNs),
                    counter(throttleStatus, cpuThrottleNs, requestedAtNs),
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

    private static CounterSignal counter(int status, long value, long ts) {
        return switch (status) {
            case OK -> CounterSignal.valid(value, ts);
            case ABSENT -> CounterSignal.unsupported(ts);
            default -> CounterSignal.transientFailure(ts);
        };
    }

    private static LongGaugeSignal gauge(int status, long value, long ts) {
        return switch (status) {
            case OK -> LongGaugeSignal.valid(value, ts);
            case ABSENT -> LongGaugeSignal.unsupported(ts);
            default -> LongGaugeSignal.transientFailure(ts);
        };
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
            String content = readFileInternal(cpusetFile);
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
            if (logDue(cpusetStr)) {
                LOGGER.warn("Failed to parse cpuset range: {}", e.getMessage());
            }
            bitSet.set(0, SystemInfo.getCpuCount());
        }
    }

    /// Resolves the CPU quota and period for the current mode into {@code quotaCpusValue},
    /// {@code quotaPeriodMicros}, {@code quotaStatus}, {@code periodStatus}, and publishes the exact
    /// quota signal. A quota file that is present but unreadable, malformed, or has a non-positive
    /// period is FAILED, never Infinity/NaN. No quota file (or an unlimited quota) falls back to the
    /// effective-CPU count, as before.
    private void readQuota(long ts) {
        quotaStatus = OK;
        periodStatus = OK;
        quotaPeriodMicros = DEFAULT_PERIOD_MICROS;
        quotaCpusValue = effectiveCpus.cardinality();

        if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V2 || paths.getMode() == LinuxPaths.CgroupMode.HYBRID) {
            String content = readSource(paths.resolveV2Path("cpu.max"), SRC_CPU_MAX);
            if (content == null) {
                if (readStatus == FAILED) {
                    quotaStatus = FAILED;
                    periodStatus = FAILED;
                }
            } else {
                long period = LinuxProcParsers.parseCpuMaxPeriodMicros(content);
                if (period <= 0L || period > Long.MAX_VALUE / 1000L) {
                    quotaStatus = FAILED;
                    periodStatus = FAILED;
                } else {
                    quotaPeriodMicros = period;
                    if (!LinuxProcParsers.isCpuMaxUnlimited(content)) {
                        long quota = LinuxProcParsers.parseCpuMaxQuotaMicros(content);
                        if (quota > 0L) {
                            quotaCpusValue = (double) quota / (double) period;
                        } else {
                            quotaStatus = FAILED;
                        }
                    }
                }
            }
        } else if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V1) {
            String qContent = readSource(paths.resolveV1Path("cpu", "cpu.cfs_quota_us"), SRC_CFS_QUOTA);
            if (qContent == null) {
                if (readStatus == FAILED) {
                    quotaStatus = FAILED;
                }
            } else {
                String trimmed = qContent.trim();
                long quota = "-1".equals(trimmed) ? -1L : LinuxProcParsers.parseSingleLong(trimmed);
                String pContent = readSource(paths.resolveV1Path("cpu", "cpu.cfs_period_us"), SRC_CFS_PERIOD);
                long period = DEFAULT_PERIOD_MICROS;
                if (pContent != null) {
                    period = LinuxProcParsers.parseSingleLong(pContent);
                    if (period <= 0L || period > Long.MAX_VALUE / 1000L) {
                        periodStatus = FAILED;
                        quotaStatus = FAILED;
                    }
                } else if (readStatus == FAILED) {
                    periodStatus = FAILED;
                    quotaStatus = FAILED;
                }
                if (periodStatus == OK) {
                    quotaPeriodMicros = period;
                    if (quota > 0L) {
                        quotaCpusValue = (double) quota / (double) period;
                    } else if (quota != -1L) {
                        quotaStatus = FAILED;
                    }
                }
            }
        }

        if (quotaStatus != OK) {
            quotaCpusValue = 0.0;
            quotaExact = DoubleGaugeSignal.transientFailure(ts);
        } else {
            quotaExact = DoubleGaugeSignal.valid(quotaCpusValue, ts);
        }
    }

    private void readCpuUsageNs() {
        usageStatus = ABSENT;
        cpuUsageNs = 0L;
        String content = null;
        long v = -1L;
        long scale = 1L;
        boolean keyPresent = false;
        if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V2 || paths.getMode() == LinuxPaths.CgroupMode.HYBRID) {
            content = readSource(paths.resolveV2Path("cpu.stat"), SRC_CPU_STAT);
            if (content != null) {
                v = LinuxProcParsers.parseKeyedLong(content, "usage_usec");
                keyPresent = LinuxProcParsers.hasKeyedLine(content, "usage_usec");
                scale = 1000L;
            }
        } else if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V1) {
            content = readSource(paths.resolveV1Path("cpuacct", "cpuacct.usage"), SRC_CPUACCT);
            if (content != null) {
                v = LinuxProcParsers.parseSingleLong(content);
                keyPresent = true;
            }
        } else {
            return;
        }
        usageStatus = counterStatus(content, v, scale, keyPresent, KEY_USAGE);
        if (usageStatus == OK) {
            cpuUsageNs = v * scale;
        }
    }

    private void readCpuThrottleNs() {
        throttleStatus = ABSENT;
        cpuThrottleNs = 0L;
        String content = null;
        long v = -1L;
        long scale = 1L;
        boolean keyPresent = false;
        if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V2 || paths.getMode() == LinuxPaths.CgroupMode.HYBRID) {
            content = readSource(paths.resolveV2Path("cpu.stat"), SRC_CPU_STAT);
            if (content != null) {
                v = LinuxProcParsers.parseKeyedLong(content, "throttled_usec");
                keyPresent = LinuxProcParsers.hasKeyedLine(content, "throttled_usec");
                scale = 1000L;
            }
        } else if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V1) {
            content = readSource(paths.resolveV1Path("cpu", "cpu.stat"), SRC_V1_CPU_STAT);
            if (content != null) {
                v = LinuxProcParsers.parseKeyedLong(content, "throttled_time");
                keyPresent = LinuxProcParsers.hasKeyedLine(content, "throttled_time");
            }
        } else {
            return;
        }
        throttleStatus = counterStatus(content, v, scale, keyPresent, KEY_THROTTLE);
        if (throttleStatus == OK) {
            cpuThrottleNs = v * scale;
        }
    }

    /// Maps a read plus a parsed value to a status. File missing/unreadable: the read status. Key line
    /// absent: UNSUPPORTED, unless that key was parsed before (then it vanished: FAILED). Key line
    /// present but malformed or overflowing: FAILED. Marks the key seen on success.
    private int counterStatus(String content, long parsed, long scale, boolean keyPresent, int keyBit) {
        if (content == null) {
            return readStatus;
        }
        if (!keyPresent) {
            return (seenMask & keyBit) != 0 ? FAILED : ABSENT;
        }
        if (parsed < 0L || parsed > Long.MAX_VALUE / scale) {
            return FAILED;
        }
        seenMask |= keyBit;
        return OK;
    }

    private void updateCpuPressure() {
        pressureStatus = OK;
        if (paths.getMode() == LinuxPaths.CgroupMode.BARE_HOST) {
            String procStat = readSource(LinuxPaths.PROC_STAT, SRC_PROC_STAT);
            if (procStat == null) {
                pressureStatus = readStatus;
                Arrays.fill(perCpuWaitStallNs, 0.0);
                return;
            }
            int n = procStat.length();
            int i = 0;
            while (i < n) {
                int e = procStat.indexOf('\n', i);
                if (e < 0) {
                    e = n;
                }
                if (e - i > 3 && procStat.startsWith("cpu", i) && Character.isDigit(procStat.charAt(i + 3))) {
                    if (!LinuxProcParsers.parseStatLine(procStat, i, e, statOut)) {
                        pressureStatus = FAILED;
                    } else {
                        int cpuId = (int) statOut[0];
                        if (cpuId < SystemInfo.getCpuCount()) {
                            long iowait = statOut[1];
                            long steal = statOut[2];

                            long deltaIowait = Math.max(0, iowait - lastCpuIowaitJiffies[cpuId]);
                            long deltaSteal = Math.max(0, steal - lastCpuStealJiffies[cpuId]);

                            lastCpuIowaitJiffies[cpuId] = iowait;
                            lastCpuStealJiffies[cpuId] = steal;

                            perCpuWaitStallNs[cpuId] = (deltaIowait + deltaSteal) * 10_000_000.0;
                        }
                    }
                }
                i = e + 1;
            }
        } else {
            // Cgroup mode (v1/v2/hybrid): Aggregate PSI propagation
            String content = readSource(paths.resolveV2Path("cpu.pressure"), SRC_CPU_PRESSURE);
            if (content == null) {
                pressureStatus = readStatus;
                Arrays.fill(perCpuWaitStallNs, 0.0);
                return;
            }
            long totalUs = LinuxProcParsers.parsePsiSomeTotal(content);
            if (totalUs < 0L || totalUs > Long.MAX_VALUE / 1000L) {
                pressureStatus = FAILED;
                Arrays.fill(perCpuWaitStallNs, 0.0);
                return;
            }
            long currentTotalStallNs = totalUs * 1000L;
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

    /// Fills {@code memMax/memCurrent} and their statuses. An unlimited cgroup limit (or no cgroup)
    /// falls back to host MemTotal; a cgroup limit that cannot be read is FAILED rather than
    /// silently replaced by the host total.
    private void readMemoryStats() {
        long cgroupMax = -1L;
        boolean maxFailed = false;
        memCurStatus = ABSENT;
        memCurrent = 0L;

        if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V2 || paths.getMode() == LinuxPaths.CgroupMode.HYBRID) {
            String content = readSource(paths.resolveV2Path("memory.max"), SRC_MEM_MAX);
            if (content != null) {
                if (!"max".equals(content.trim())) {
                    cgroupMax = LinuxProcParsers.parseSingleLong(content);
                    maxFailed = cgroupMax <= 0L;
                    if (maxFailed) {
                        cgroupMax = -1L;
                    }
                }
            } else if (readStatus == FAILED) {
                maxFailed = true;
            }
            readMemCurrent(paths.resolveV2Path("memory.current"));
        } else if (paths.getMode() == LinuxPaths.CgroupMode.CGROUP_V1) {
            String content = readSource(paths.resolveV1Path("memory", "memory.limit_in_bytes"), SRC_MEM_MAX);
            if (content != null) {
                long val = LinuxProcParsers.parseSingleLong(content);
                if (val < 0L) {
                    maxFailed = true;
                } else if (val < 9223372036854770000L) {
                    cgroupMax = val;
                }
            } else if (readStatus == FAILED) {
                maxFailed = true;
            }
            readMemCurrent(paths.resolveV1Path("memory", "memory.usage_in_bytes"));
        }

        if (maxFailed) {
            memMaxStatus = FAILED;
            memMax = 0L;
        } else if (cgroupMax > 0L) {
            memMaxStatus = OK;
            memMax = cgroupMax;
        } else {
            String meminfo = readSource(LinuxPaths.PROC_MEMINFO, SRC_MEMINFO);
            if (meminfo == null) {
                memMaxStatus = readStatus;
                memMax = 0L;
            } else {
                long kb = LinuxProcParsers.parseMemInfoKb(meminfo, "MemTotal");
                if (kb < 0L || kb > Long.MAX_VALUE / 1024L) {
                    memMaxStatus = FAILED;
                    memMax = 0L;
                } else {
                    memMaxStatus = OK;
                    memMax = kb * 1024L;
                }
            }
        }
    }

    private void readMemCurrent(Path path) {
        String content = readSource(path, SRC_MEM_CUR);
        if (content == null) {
            memCurStatus = readStatus;
            return;
        }
        long v = LinuxProcParsers.parseSingleLong(content);
        if (v < 0L) {
            memCurStatus = FAILED;
        } else {
            memCurStatus = OK;
            memCurrent = v;
        }
    }

    private void readFilteredDiskIoBytes() {
        diskIoBytes = 0L;
        String diskstats = readSource(diskstatsPath, SRC_DISKSTATS);
        if (diskstats == null) {
            diskStatus = readStatus;
            return;
        }
        diskStatus = OK;
        long totalBytes = 0;
        int n = diskstats.length();
        int i = 0;
        while (i < n) {
            int e = diskstats.indexOf('\n', i);
            if (e < 0) {
                e = n;
            }
            int r = LinuxProcParsers.parseDiskstatsLine(diskstats, i, e, diskOut);
            if (r < 0) {
                diskStatus = FAILED;
            } else if (r > 0
                    && isFilteredBlockDevice(
                            (int) diskOut[0], diskstats.substring((int) diskOut[1], (int) diskOut[2]))) {
                totalBytes += (diskOut[3] + diskOut[4]) * 512L;
            }
            i = e + 1;
        }
        if (diskStatus == OK) {
            diskIoBytes = totalBytes;
        }
    }

    /// I/O stall comes from io.pressure (PSI) when the host has it, otherwise from diskstats. The
    /// source is chosen at first sight and kept: falling back after a PSI failure, or switching to PSI
    /// once it appears, would splice two counters with unrelated baselines. A failing chosen source is
    /// reported as TRANSIENT_FAILURE.
    private void readIoStallNs() {
        ioStallNs = 0L;
        if (ioStallSource != IO_SRC_DISKSTATS) {
            String content = readSource(
                    ioPressureOverride != null ? ioPressureOverride : paths.resolveV2Path("io.pressure"),
                    SRC_IO_PRESSURE);
            if (content != null) {
                long totalUs = LinuxProcParsers.parsePsiSomeTotal(content);
                if (totalUs >= 0L && totalUs <= Long.MAX_VALUE / 1000L) {
                    ioStallSource = IO_SRC_PRESSURE;
                    ioStallStatus = OK;
                    ioStallNs = totalUs * 1000L;
                } else {
                    ioStallStatus = FAILED;
                }
                return;
            }
            if (readStatus == FAILED || ioStallSource == IO_SRC_PRESSURE) {
                ioStallStatus = FAILED;
                return;
            }
        }
        String diskstats = readSource(diskstatsPath, SRC_DISKSTATS);
        if (diskstats == null) {
            ioStallStatus = readStatus;
            return;
        }
        // Commit to diskstats only after it was actually read, so that when neither source exists at
        // first sight a PSI file that appears later can still be chosen.
        ioStallSource = IO_SRC_DISKSTATS;
        ioStallStatus = OK;
        long totalMs = 0;
        int n = diskstats.length();
        int i = 0;
        while (i < n) {
            int e = diskstats.indexOf('\n', i);
            if (e < 0) {
                e = n;
            }
            int r = LinuxProcParsers.parseDiskstatsLine(diskstats, i, e, diskOut);
            if (r < 0) {
                ioStallStatus = FAILED;
            } else if (r > 0
                    && isFilteredBlockDevice(
                            (int) diskOut[0], diskstats.substring((int) diskOut[1], (int) diskOut[2]))) {
                totalMs += diskOut[5];
            }
            i = e + 1;
        }
        if (ioStallStatus == OK) {
            ioStallNs = totalMs > Long.MAX_VALUE / 1_000_000L ? 0L : totalMs * 1_000_000L;
            if (totalMs > Long.MAX_VALUE / 1_000_000L) {
                ioStallStatus = FAILED;
            }
        }
    }

    private double readCpuFrequencyHz() {
        Path curFreq = paths.CPU_INFO_BASE.resolve("cpu0/cpufreq/scaling_cur_freq");
        if (Files.exists(curFreq)) {
            String content = readFileInternal(curFreq);
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
                            String content = readFileInternal(tempFile);
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

    /// Reads a source file and records ABSENT/FAILED in {@link #readStatus} when it returns null. A
    /// source that was read successfully before and is now missing is FAILED (it vanished), while one
    /// never seen is ABSENT (this host does not provide it).
    private String readSource(Path path, int seenBit) {
        String s = readFileInternal(path);
        if (s != null) {
            seenMask |= seenBit;
        } else if (readStatus == ABSENT && (seenMask & seenBit) != 0) {
            readStatus = FAILED;
        }
        return s;
    }

    /// Reads at most {@link #MAX_FILE_BYTES} bytes. Returns null for a missing file (readStatus
    /// ABSENT), an I/O error, or a file larger than the cap (readStatus FAILED). Callers hold the
    /// provider lock.
    private String readFileInternal(Path path) {
        if (path == null || !Files.exists(path)) {
            readStatus = ABSENT;
            return null;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            int total = 0;
            long pos = 0;
            while (true) {
                buffer.clear();
                int bytesRead = channel.read(buffer, pos);
                if (bytesRead <= 0) {
                    break;
                }
                if (total + bytesRead > MAX_FILE_BYTES) {
                    if (logDue(path.toString())) {
                        LOGGER.warn("File {} exceeds {} bytes", path, MAX_FILE_BYTES);
                    }
                    readStatus = FAILED;
                    return null;
                }
                if (total + bytesRead > scratch.length) {
                    scratch = Arrays.copyOf(
                            scratch, Math.min(MAX_FILE_BYTES, Math.max(scratch.length * 2, total + bytesRead)));
                }
                pos += bytesRead;
                buffer.flip();
                buffer.get(scratch, total, bytesRead);
                total += bytesRead;
            }
            readStatus = OK;
            return new String(scratch, 0, total, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (logDue(path.toString())) {
                LOGGER.warn("Error reading file {}: {}", path, e.getMessage());
            }
            readStatus = Files.exists(path) ? FAILED : ABSENT;
            return null;
        }
    }

    /// Reads a file of at most 1 MiB, holding the provider lock for the duration. Returns `null` on
    /// failure (the file is missing or unreadable) or when the file is larger than 1 MiB; a truncated
    /// read is never returned because a partial counter file would parse as a plausible wrong value.
    public @Nullable String readFileBounded(Path path) {
        acquireLock();
        try {
            return readFileInternal(path);
        } finally {
            releaseLock();
        }
    }

    /// True (and records the time) when a warning for `key` may be logged now. Callers build the
    /// message only after this returns true, so a persistently failing file allocates nothing per tick.
    private boolean logDue(String key) {
        long now = System.nanoTime();
        Long last = lastLoggedNs.get(key);
        if (last == null || (now - last) >= LOG_RATE_LIMIT_NS) {
            lastLoggedNs.put(key, now);
            return true;
        }
        return false;
    }

    @Override
    public void close() {}
}
