package euhedral.hardware_utils.linux;

import euhedral.hardware_utils.SystemInfo;
import euhedral.hardware_utils.common.SystemSnapshotProvider;
import euhedral.hardware_utils.common.SystemUtilization.MemorySnapshotIdx;
import euhedral.hardware_utils.common.SystemUtilization.SystemSnapshot;
import euhedral.hardware_utils.common.UnmodifiableBitSet;
import it.unimi.dsi.fastutil.longs.LongArraySet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Reads from cgroupV2 files to build a SystemSnapshot object.
///
/// This class is thread-safe.
public class CgroupV2Resources implements SystemSnapshotProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(CgroupV2Resources.class);

    private static final byte[] MAX_KEY = "max".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CPU_KEY = "cpu".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SOME_KEY = "some".getBytes(StandardCharsets.US_ASCII);
    private static final LongSet DEVICE_KEYS = new LongArraySet();
    private static final byte[] RBYTES_KEY = "rbytes".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WBYTES_KEY = "wbytes".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INACTIVE_FILE =
            "inactive_file".getBytes(StandardCharsets.US_ASCII);

    static {
        Path devicesPath = Paths.get("/proc/devices");
        if (!Files.exists(devicesPath)) {
            DEVICE_KEYS.add(7);
        } else {
            try (Stream<String> lines = Files.lines(devicesPath)) {
                lines.forEach(line -> {
                    if (line.trim().endsWith(" loop")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 1) {
                            DEVICE_KEYS.add(Long.parseLong(parts[0]));
                        }
                    }
                });
            } catch (Throwable t) {
                DEVICE_KEYS.add(7);
                LOGGER.error("Failed to read storage device list.", t);
            }
        }
    }

    private final FileChannel procStatChannel;
    private final FileChannel effectiveCpuChannel;
    private final FileChannel cpuMaxChannel;
    private final FileChannel cpuPressureChannel;
    private final FileChannel cpuStatChannel;
    private final FileChannel memoryMaxChannel;
    private final FileChannel memoryCurrentChannel;
    private final FileChannel memoryStatChannel;
    private final FileChannel ioStatChannel;
    private final ByteBuffer buffer = ByteBuffer.allocateDirect(65_536);

    private final BitSet effectiveCpus = new BitSet(SystemInfo.getCpuCount());
    private final long[] lastCpuActiveTime = new long[SystemInfo.getCpuCount()];
    private final long[] quota = new long[2];
    private final double[] pressure = new double[SystemInfo.getCpuCount()];
    private final double[] jiffyDeltas = new double[SystemInfo.getCpuCount()];
    private final long[] memorySnapshot = new long[3];
    private final CpuMetrics cpuMetrics = new CpuMetrics();

    private final AtomicBoolean wip = new AtomicBoolean(false);
    private final AtomicReference<SystemSnapshot> snapshot = new AtomicReference<>();

    private long lastTotalStallUsec = 0;

    public CgroupV2Resources() {
        FileChannel procStatChannel = null;
        FileChannel effectiveCpuChannel = null;
        FileChannel cpuMaxChannel = null;
        FileChannel cpuPressureChannel = null;
        FileChannel cpuStatChannel = null;
        FileChannel memoryMaxChannel = null;
        FileChannel memoryCurrentChannel = null;
        FileChannel memoryStatChannel = null;
        FileChannel ioStatChannel = null;

        try {
            procStatChannel = FileChannel.open(LinuxPaths.PROC_STAT, StandardOpenOption.READ);
        } catch (Throwable ignored) {

        }
        try {
            effectiveCpuChannel = FileChannel.open(LinuxPaths.EFFECTIVE_CPU,
                    StandardOpenOption.READ);
        } catch (Throwable ignored) {

        }
        try {
            cpuMaxChannel = FileChannel.open(LinuxPaths.CPU_MAX, StandardOpenOption.READ);
        } catch (Throwable ignored) {

        }
        try {
            cpuPressureChannel = FileChannel.open(LinuxPaths.CPU_PRESSURE, StandardOpenOption.READ);
        } catch (Throwable ignored) {

        }
        try {
            cpuStatChannel = FileChannel.open(LinuxPaths.CPU_STAT, StandardOpenOption.READ);
        } catch (Throwable ignored) {

        }
        try {
            memoryMaxChannel = FileChannel.open(LinuxPaths.MEMORY_MAX, StandardOpenOption.READ);
        } catch (Throwable ignored) {

        }
        try {
            memoryCurrentChannel = FileChannel.open(LinuxPaths.MEMORY_CURRENT,
                    StandardOpenOption.READ);
        } catch (Throwable ignored) {

        }
        try {
            memoryStatChannel = FileChannel.open(LinuxPaths.MEMORY_STAT, StandardOpenOption.READ);
        } catch (Throwable ignored) {

        }
        try {
            ioStatChannel = FileChannel.open(LinuxPaths.IO_STAT, StandardOpenOption.READ);
        } catch (Throwable ignored) {

        }

        this.procStatChannel = procStatChannel;
        this.effectiveCpuChannel = effectiveCpuChannel;
        this.cpuMaxChannel = cpuMaxChannel;
        this.cpuPressureChannel = cpuPressureChannel;
        this.cpuStatChannel = cpuStatChannel;
        this.memoryMaxChannel = memoryMaxChannel;
        this.memoryCurrentChannel = memoryCurrentChannel;
        this.memoryStatChannel = memoryStatChannel;
        this.ioStatChannel = ioStatChannel;
    }

    @Override
    public SystemSnapshot getSnapshot() {
        if (!wip.compareAndSet(false, true)) {
            while (wip.get()) {
                Thread.onSpinWait();
            }
            return snapshot.get();
        }

        SystemSnapshot snapshot = this.snapshot.get();
        try {
            long now = System.nanoTime();

            // --- CPU ---
            int availableCpus = SystemInfo.CPU_COUNT;
            updateEffectiveCpuSet();

            updatePerCpuPressure();
            updateCpuQuota();

            long quota = this.quota[0];
            long period = this.quota[1];
            double quotaCpus = (double) quota / period;

            cpuMetrics.updateCpuStats();

            long cpuUsage = cpuMetrics.usageNs;
            long cpuThrottle = cpuMetrics.throttledNs;

            // --- Memory & IO ---
            updateMemory();
            long ioBytes = getDiskIoBytes();

            snapshot =
                    SystemSnapshot.create(now,
                            availableCpus, quotaCpus, period,
                            cpuUsage, cpuThrottle,
                            UnmodifiableBitSet.wrap((BitSet) effectiveCpus.clone()),
                            pressure,
                            memorySnapshot,
                            ioBytes
                    );
        } catch (Throwable t) {
            LOGGER.error("Error generating SystemSnapshot.", t);
        } finally {
            this.snapshot.set(snapshot);
            wip.set(false);
        }
        return snapshot;
    }

    // ----- CPU -----

    /// Reads cpuset.cpus.effective ande parses the cpus into a BitSet
    private void updateEffectiveCpuSet() {
        this.effectiveCpus.clear();
        int len = readToBuffer(this.effectiveCpuChannel);
        if (len <= 0) {
            this.effectiveCpus.set(0, Runtime.getRuntime().availableProcessors());
            return;
        }

        int pos = 0;
        while (pos < len) {
            if (isEndOfLine(pos)) {
                break;
            }

            long start;
            long end;

            if (moveForwardToTarget(pos, len, '-')) {
                start = parseLongAt(pos);

                pos = this.buffer.position() + 1;
                end = parseLongAt(pos);
            } else {
                start = parseLongAt(pos);
                end = start;
            }
            this.effectiveCpus.set((int) start, (int) end + 1);

            if (!moveForwardToTarget(this.buffer.position(), len, ',')) {
                break;
            }
            pos = this.buffer.position() + 1;
        }
    }

    /// Reads cpu.max and gets the CPU quota and period.
    private void updateCpuQuota() {
        this.quota[0] = this.effectiveCpus.cardinality();
        this.quota[1] = 100_000L;

        int len = readToBuffer(this.cpuMaxChannel);

        if (len <= 0 || matchKey(0, MAX_KEY)) {
            return;
        }

        moveForwardToTarget(0, len, ' ');

        this.quota[0] = parseLongAt(0);
        this.quota[1] = parseLongAt(this.buffer.position() + 1);
    }

    /// Reads /proc/stat and cpu.pressure to estimate the pressure per cpu. Updates the pressure
    /// values and puts them in nanosecond scale.
    private void updatePerCpuPressure() {
        long currentTotalStall = parseCgroupTotalStall();
        long stallDelta = Math.max(0, currentTotalStall - this.lastTotalStallUsec);
        this.lastTotalStallUsec = currentTotalStall;

        if (stallDelta == 0) {
            return;
        }

        Arrays.fill(this.jiffyDeltas, 0);

        long totalMeasuredActivity = 0;
        int len = readToBuffer(this.procStatChannel);

        int pos = 0;
        while (pos < len) {
            if (matchKey(pos, CPU_KEY) && this.buffer.get(pos + 3) != ' ') {
                int cpuId = (int) parseLongAt(pos + 3);
                if (cpuId >= SystemInfo.CPU_COUNT) {
                    if (!moveToNextLine(this.buffer.position(), len)) {
                        break;
                    }
                    pos = this.buffer.position() + 1;
                    continue;
                }
                moveForwardToTarget(pos, len, ' ');
                pos = this.buffer.position() + 1;

                long activeTime = parseActiveJiffies(pos, len);
                long delta = Math.max(0, activeTime - this.lastCpuActiveTime[cpuId]);

                this.lastCpuActiveTime[cpuId] = activeTime;
                this.jiffyDeltas[cpuId] = delta;
                totalMeasuredActivity += delta;
            }
            if (!moveToNextLine(this.buffer.position(), len)) {
                break;
            }
            pos = this.buffer.position() + 1;
        }

        if (totalMeasuredActivity > 0) {
            double multiplier = (double) stallDelta / totalMeasuredActivity;
            for (int i = 0; i < this.pressure.length; i++) {
                this.pressure[i] = this.jiffyDeltas[i] * multiplier;
            }
        }
    }

    /// Reads cpu.pressure
    ///
    /// @return total cpu pressure
    private long parseCgroupTotalStall() {
        int len = readToBuffer(this.cpuPressureChannel);
        if (len <= 0) {
            return 0;
        }

        if (matchKey(0, SOME_KEY)) {
            moveForwardToTarget(0, len, '\n');
            moveBackToTarget(this.buffer.position(), '=');
            return parseLongAt(this.buffer.position() + 1) * 1000;
        }
        return 0;
    }

    /// Parses a line from /proc/stat and returns the sum of jiffies.
    ///
    /// @param pos scan start
    /// @param len scan end
    /// @return jiffies
    private long parseActiveJiffies(int pos, int len) {
        long sum = 0;
        int column = 0;

        int cursor = pos;
        while (cursor < len) {
            if (isEndOfLine(cursor)) {
                break;
            }

            // /proc/stat cpu columns:
            // cpu0 0:user, 1:nice, 2:system, 3:idle, 4:iowait, 5:irq, 6:softirq, 7:steal
            // Active = 0, 1, 2, 5, 6, 7
            if (column < 3 || column > 4) {
                sum += parseLongAt(cursor);
            }
            if (moveForwardToTarget(cursor, len, ' ')) {
                cursor = this.buffer.position() + 1;
            } else {
                break;
            }
            column++;
        }
        this.buffer.position(pos);
        return sum * 1000;
    }

    // ----- MEMORY -----

    /// Reads memory.max, memory.current, memory.stat to refresh the memory snapshot (max, usage,
    /// inactive_file).
    private void updateMemory() {
        long memoryLimit = Runtime.getRuntime().maxMemory();
        if (readToBuffer(this.memoryMaxChannel) > 0 && !matchKey(0, MAX_KEY)) {
            memoryLimit = parseLongAt(0);
        }

        long memoryCurrent = 0;
        if (readToBuffer(this.memoryCurrentChannel) > 0) {
            memoryCurrent = parseLongAt(0);
        }

        long inactiveFile = 0;
        int pos = 0;
        int len = readToBuffer(this.memoryStatChannel);
        while (pos < len) {
            if (isEndOfLine(pos)) {
                break;
            }
            if (matchKey(pos, INACTIVE_FILE)) {
                moveForwardToTarget(pos, len, ' ');
                inactiveFile = parseLongAt(this.buffer.position() + 1);
            }
            if (!moveToNextLine(pos, len)) {
                break;
            }
            pos = this.buffer.position();
        }

        this.memorySnapshot[MemorySnapshotIdx.MEMORY_LIMIT.idx] = memoryLimit;
        this.memorySnapshot[MemorySnapshotIdx.MEMORY_USAGE.idx] = memoryCurrent;
        this.memorySnapshot[MemorySnapshotIdx.INACTIVE_FILE.idx] = inactiveFile;
    }

    // ----- IO -----

    /// Reads io.stat and returns the sum of read/written bytes for devices assigned to the group.
    ///
    /// @return bytes
    private long getDiskIoBytes() {
        int len = readToBuffer(this.ioStatChannel);
        if (len <= 0) {
            return 0;
        }

        long currentRead = 0;
        long currentWrite = 0;

        int pos = 0;
        while (pos < len) {
            if (isEndOfLine(pos)) {
                break;
            }

            long major = parseLongAt(pos);
            if (!DEVICE_KEYS.contains(major)) {
                if (!moveToNextLine(pos, len)) {
                    break;
                }
                pos = this.buffer.position();
                continue;
            }

            moveForwardToTarget(pos, len, ' ');
            pos = this.buffer.position() + 1;

            while (pos < len) {
                if (matchKey(pos, RBYTES_KEY)) {
                    moveForwardToTarget(pos, len, '=');
                    pos = this.buffer.position() + 1;

                    currentRead += parseLongAt(pos);

                    moveForwardToTarget(pos, len, ' ');
                    pos = this.buffer.position() + 1;
                }
                if (matchKey(pos, WBYTES_KEY)) {
                    moveForwardToTarget(pos, len, '=');
                    pos = this.buffer.position() + 1;

                    currentWrite += parseLongAt(pos);
                }
                if (moveForwardToTarget(pos, len, ' ')) {
                    pos = this.buffer.position() + 1;
                    continue;
                }
                if (!moveToNextLine(pos, len)) {
                    break;
                }
                pos = this.buffer.position();
            }
        }

        return currentRead + currentWrite;
    }

    /// Reads from the file channel to the buffer.
    ///
    /// @param channel FileChannel to read
    /// @return bytes read or -1 if failure
    private int readToBuffer(FileChannel channel) {
        try {
            this.buffer.clear();
            channel.position(0);
            int bytesRead = channel.read(this.buffer);
            this.buffer.flip();

            return bytesRead;
        } catch (Exception e) {
            LOGGER.error("Failed to read file.", e);
            return -1;
        }
    }

    /// @return buffer.get(pos) == '\n' || buffer.get(pos) == '\0'
    private boolean isEndOfLine(int pos) {
        return this.buffer.get(pos) == '\n' || this.buffer.get(pos) == '\0';
    }

    /// Moves the buffer backwards to the index of the target character. Does not move if there is
    /// no match.
    ///
    /// @param pos    start of the scan
    /// @param target character to match
    /// @return success
    private boolean moveBackToTarget(int pos, char target) {
        while (pos > -1) {
            if (this.buffer.get(pos) == target) {
                this.buffer.position(pos);
                return true;
            }
            pos--;
        }
        return false;
    }

    /// Moves the buffer forward to the index of the target character. Does not move if there is no
    /// match.
    ///
    /// @param start  start of the scan
    /// @param end    end of the scan
    /// @param target character to match
    /// @return success
    private boolean moveForwardToTarget(int start, int end, char target) {
        for (int i = start; i < end; i++) {
            if (this.buffer.get(i) == target) {
                this.buffer.position(i);
                return true;
            }
        }
        return false;
    }

    /// Moves the buffer to the start of the next line. Does not move if there is no next line.
    ///
    /// @return success
    private boolean moveToNextLine(int start, int end) {
        if (!moveForwardToTarget(start, end, '\n')) {
            return false;
        }
        this.buffer.get();
        return true;
    }

    /// Parses the long value starting at the position.
    ///
    /// @param pos start of the scan
    /// @return value
    private long parseLongAt(int pos) {
        long res = 0;

        while (pos < this.buffer.limit()) {
            byte c = this.buffer.get(pos);

            if (c < '0' || c > '9') {
                break;
            }

            res = res * 10 + (c - '0');
            pos++;
        }

        return res;
    }

    /// Scans the buffer and tries to match the provided key. The key is a match if the character
    /// after it is whiteSpace, '=', ',', '\t', '\r', '\n' or '\0'
    ///
    /// @param pos starting index
    /// @param key bytes to match
    /// @return success
    private boolean matchKey(int pos, byte[] key) {
        for (byte b : key) {
            if (this.buffer.get(pos) != b) {
                return false;
            }
            pos++;
        }
        byte cursor = this.buffer.get(pos);
        return cursor == ' ' || cursor == '=' || cursor == ',' || cursor == '\t' || cursor == '\r'
                || cursor == '\n' || cursor == '\0';
    }

    private class CpuMetrics {

        private static final byte[] USAGE_USEC =
                "usage_usec".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] THROTTLED_USEC =
                "throttled_usec".getBytes(StandardCharsets.US_ASCII);

        private long usageNs;
        private long throttledNs;

        /// Reads cpu.stat and gets the usage_usec and throttled_usec. Scales them to nanoseconds.
        private void updateCpuStats() {
            int len = readToBuffer(cpuStatChannel);
            if (len <= 0) {
                return;
            }

            int pos = 0;
            int parsed = 0;
            while (pos < len) {
                if (isEndOfLine(pos)) {
                    break;
                }

                if (parsed == 2) {
                    break;
                }

                if (matchKey(pos, USAGE_USEC)) {
                    moveForwardToTarget(pos, len, ' ');
                    pos = buffer.position() + 1;

                    usageNs = parseLongAt(pos) * 1_000L;
                    if (!moveToNextLine(buffer.position(), len)) {
                        break;
                    }
                    pos = buffer.position();
                    parsed++;
                }
                if (matchKey(pos, THROTTLED_USEC)) {
                    moveForwardToTarget(pos, len, ' ');
                    pos = buffer.position() + 1;

                    throttledNs = parseLongAt(pos) * 1_000L;
                    parsed++;
                }
                if (!moveToNextLine(buffer.position(), len)) {
                    break;
                }
                pos = buffer.position() + 1;
            }
        }
    }
}
