package euhedral.io.resource_monitoring.providers;

import euhedral.io.resource_monitoring.NumaMapper;
import euhedral.io.resource_monitoring.SystemUtilization.SystemSnapshot;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.BitSet;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CgroupV2Resources implements ResourceProvider {

    private static final byte[] inactiveFileBytes = "inactive_file".getBytes(
            StandardCharsets.US_ASCII);
    private static final byte[] rbytesKey = "rbytes=".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] wbytesKey = "wbytes=".getBytes(StandardCharsets.US_ASCII);

    private final Logger logger = LoggerFactory.getLogger(CgroupV2Resources.class);
    private final byte[] buffer = new byte[65_536];

    private final Path cpuMaxPath;
    private final Path cpuStatPath;
    private final Path effectiveCpuPath;
    private final Path pressurePath;
    private final Path memoryMaxPath;
    private final Path memoryCurrentPath;
    private final Path memoryStatPath;
    private final Path ioStatPath;
    private final int totalCpus;
    private final BitSet effectiveCpus;
    private final long[] lastCpuActiveTime;
    private final CpuMetrics cpuMetrics = new CpuMetrics();
    private long lastTotalStallUsec = 0;

    public CgroupV2Resources(Path cgroupPath) {
        this.cpuMaxPath = cgroupPath.resolve("cpu.max");
        this.cpuStatPath = cgroupPath.resolve("cpu.stat");
        this.effectiveCpuPath = cgroupPath.resolve("cpuset.cpus.effective");
        this.pressurePath = cgroupPath.resolve("cpu.pressure");
        this.memoryMaxPath = cgroupPath.resolve("memory.max");
        this.memoryCurrentPath = cgroupPath.resolve("memory.current");
        this.memoryStatPath = cgroupPath.resolve("memory.stat");
        this.ioStatPath = cgroupPath.resolve("io.stat");
        this.totalCpus = NumaMapper.INSTANCE.getCpuCount();
        this.effectiveCpus = new BitSet(totalCpus);
        this.lastCpuActiveTime = new long[totalCpus];
    }

    @Override
    public SystemSnapshot getSnapshot() {
        long now = System.nanoTime();

        // --- CPU ---
        int availableCpus = Runtime.getRuntime().availableProcessors();
        BitSet effectiveCpus = getEffectiveCpuSet();

        double[] pressurePerCpu = getPerCpuPressure();

        long[] cpuMax = getCpuMax();
        long quota = cpuMax[0];
        long period = cpuMax[1];
        double quotaCpus;

        if (quota > 0 && period > 0) {
            quotaCpus = (double) quota / period;
        } else {
            quotaCpus = availableCpus;
        }

        CpuMetrics cpuMetrics = getCpuMetrics();

        long cpuUsage = cpuMetrics.getUsageNs();
        long cpuThrottle = cpuMetrics.getThrottledNs();

        // --- Memory & IO ---
        long memoryLimit = getMemoryLimit();
        long memoryUsage = getMemoryUsage();
        long inactiveFile = getInactiveFile();
        long ioBytes = getIoBytes();

        return new SystemSnapshot(now, availableCpus, quotaCpus, period, cpuUsage, cpuThrottle,
                effectiveCpus,
                pressurePerCpu,
                memoryLimit, memoryUsage, inactiveFile, ioBytes);
    }

    /**
     * Reads /sys/fs/cgroup/cpuset.cpus.effective Parses "0-2,4" into [0, 1, 2, 4] Returns the count
     * of CPUs found.
     */
    public BitSet getEffectiveCpuSet() {
        effectiveCpus.clear();
        int len = readFileToBuffer(effectiveCpuPath);
        if (len <= 0) {
            return effectiveCpus;
        }

        int count = 0;
        int i = 0;
        while (i < len && buffer[i] != '\n' && buffer[i] != '\0') {
            int start = 0;
            while (i < len && buffer[i] >= '0' && buffer[i] <= '9') {
                start = start * 10 + (buffer[i++] - '0');
            }

            // It's a range: "0-3"
            if (i < len && buffer[i] == '-') {
                i++; // skip '-'
                int end = 0;
                while (i < len && buffer[i] >= '0' && buffer[i] <= '9') {
                    end = end * 10 + (buffer[i++] - '0');
                }
                for (int cpu = start; cpu <= end && count < totalCpus; cpu++) {
                    effectiveCpus.set(cpu);
                }
            } else {
                if (count < totalCpus) {
                    effectiveCpus.set(start);
                }
            }

            if (i < len && (buffer[i] == ',' || buffer[i] == ' ')) {
                i++;
            }
        }
        return effectiveCpus;
    }

    private int readFileToBuffer(Path path) {
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            return fis.read(buffer);
        } catch (IOException e) {
            logger.error("Failed to read file", e);
            return -1;
        }
    }

    public CpuMetrics getCpuMetrics() {
        cpuMetrics.updateCpuStats();
        return cpuMetrics;
    }

    public long[] getCpuMax() {
        int bytesRead = readFileToBuffer(cpuMaxPath);
        if (bytesRead <= 0) {
            return new long[]{Runtime.getRuntime().availableProcessors(), 100_000};
        }

        int spaceIdx = findByte(0, bytesRead, (byte) ' ');

        int quotaEnd = (spaceIdx == -1) ? bytesRead : spaceIdx;

        long quota = isMax(0, quotaEnd) ? -1 : parseBytesLong(0, quotaEnd);

        long period = (spaceIdx != -1) ? parseBytesLong(spaceIdx + 1, bytesRead) : 100_000;

        return new long[]{quota, period};
    }

    // --- Memory & IO ---

    private long parseBytesLong(int start, int end) {
        long res = 0;
        for (int i = start; i < end; i++) {
            byte b = buffer[i];
            if (b >= '0' && b <= '9') {
                res = res * 10 + (b - '0');
            }
        }
        return res;
    }

    private boolean isMax(int start, int end) {
        return buffer[start] == 'm' && buffer[start + 1] == 'a'
                && buffer[start + 2] == 'x';
    }

    private int findByte(int start, int end, byte target) {
        for (int i = start; i < end; i++) {
            if (buffer[i] == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return Array of stall-usec attributed to each logical CPU index.
     */
    public double[] getPerCpuPressure() {
        long currentTotalStall = parseCgroupTotalStall();
        long stallDelta = Math.max(0, currentTotalStall - lastTotalStallUsec);
        lastTotalStallUsec = currentTotalStall;

        double[] cpuPressure = new double[totalCpus];
        if (stallDelta == 0) {
            return cpuPressure;
        }

        long[] currentDeltas = new long[totalCpus];
        long totalMeasuredActivity = 0;

        // Use FileInputStream for raw byte access to /proc/stat
        try (FileInputStream fis = new FileInputStream("/proc/stat")) {
            int len = fis.read(buffer);
            int pos = 0;

            while (pos < len) {
                if (buffer[pos] == 'c' && buffer[pos + 1] == 'p' && buffer[pos + 2] == 'u'
                        && buffer[pos + 3] != ' ') {
                    pos += 3; // Move past "cpu"

                    int cpuId = 0;
                    while (buffer[pos] >= '0' && buffer[pos] <= '9') {
                        cpuId = cpuId * 10 + (buffer[pos++] - '0');
                    }

                    if (cpuId < totalCpus) {
                        long activeTime = parseActiveJiffies(pos, len);
                        long delta = Math.max(0, activeTime - lastCpuActiveTime[cpuId]);

                        lastCpuActiveTime[cpuId] = activeTime;
                        currentDeltas[cpuId] = delta;
                        totalMeasuredActivity += delta;
                    }
                }

                while (pos < len && buffer[pos] != '\n') {
                    pos++;
                }
                pos++;
            }
        } catch (IOException e) {
            logger.error("Failed to read per-cpu pressure", e);
        }

        if (totalMeasuredActivity > 0) {
            double multiplier = (double) stallDelta / totalMeasuredActivity;
            for (int i = 0; i < totalCpus; i++) {
                cpuPressure[i] = currentDeltas[i] * multiplier;
            }
        }
        return cpuPressure;
    }

    /**
     * Parses active jiffies by jumping to specific column positions
     */
    private long parseActiveJiffies(int pos, int len) {
        long sum = 0;
        int column = 0;

        while (pos < len && buffer[pos] != '\n') {
            // Skip spaces
            while (pos < len && buffer[pos] == ' ') {
                pos++;
            }
            if (pos >= len || buffer[pos] == '\n') {
                break;
            }

            // /proc/stat columns (0-indexed):
            // 0:user, 1:nice, 2:system, 3:idle, 4:iowait, 5:irq, 6:softirq, 7:steal
            // Active = 0, 1, 2, 5, 6, 7
            if (column == 0 || column == 1 || column == 2 || column == 5 || column == 6
                    || column == 7) {
                long val = 0;
                while (pos < len && buffer[pos] >= '0' && buffer[pos] <= '9') {
                    val = val * 10 + (buffer[pos++] - '0');
                }
                sum += val;
            } else {
                // Just skip the number
                while (pos < len && buffer[pos] >= '0' && buffer[pos] <= '9') {
                    pos++;
                }
            }
            column++;
        }
        return sum;
    }

    private long parseCgroupTotalStall() {
        try (FileInputStream fis = new FileInputStream(pressurePath.toFile())) {
            int len = fis.read(buffer);
            int pos = 0;
            while (pos < len) {
                if (buffer[pos] == 's' && buffer[pos + 1] == 'o' && buffer[pos + 2] == 'm') {
                    // Fast forward to "total="
                    for (int i = pos; i < len - 6; i++) {
                        if (buffer[i] == 't' && buffer[i + 1] == 'o' && buffer[i + 2] == 't') {
                            return parseLongAt(i + 6, len);
                        }
                    }
                }
                while (pos < len && buffer[pos] != '\n') {
                    pos++;
                }
                pos++;
            }
        } catch (IOException e) {
            logger.error("Failed to read cgroup total stall", e);
        }
        return 0;
    }

    private long parseLongAt(int pos, int len) {
        long res = 0;
        while (pos < len && buffer[pos] >= '0' && buffer[pos] <= '9') {
            res = res * 10 + (buffer[pos++] - '0');
        }
        return res;
    }

    public long getMemoryLimit() {
        int len = readFileToBuffer(memoryMaxPath);
        return isMax(0, len) ? Runtime.getRuntime().maxMemory() : parseBytesLong(0, len);
    }

    public long getMemoryUsage() {
        int len = readFileToBuffer(memoryCurrentPath);
        return parseBytesLong(0, len);
    }

    public long getInactiveFile() {
        int len = readFileToBuffer(memoryStatPath);
        if (len <= 0) {
            return 0;
        }

        int pos = 0;
        while (pos < len) {
            if (buffer[pos] == 'i') {
                if (matchKey(pos, inactiveFileBytes)) {
                    return parseLongAfterKey(pos + 13, len);
                }
            }
            // Move to next line
            while (pos < len && buffer[pos] != '\n') {
                pos++;
            }
            pos++;
        }
        return 0;
    }

    private boolean matchKey(int pos, byte[] key) {
        for (int i = 0; i < key.length; i++) {
            if (buffer[pos + i] != key[i]) {
                return false;
            }
        }
        byte following = buffer[pos + key.length];
        return following == ' ' || following == '\t';
    }

    private long parseLongAfterKey(int pos, int len) {
        // Skip spaces/tabs between key and value
        while (pos < len && (buffer[pos] == ' ' || buffer[pos] == '\t')) {
            pos++;
        }
        long res = 0;
        while (pos < len && buffer[pos] >= '0' && buffer[pos] <= '9') {
            res = res * 10 + (buffer[pos++] - '0');
        }
        return res;
    }

    public long getIoBytes() {
        int len = readFileToBuffer(ioStatPath);
        if (len <= 0) {
            return 0;
        }

        long currentRead = 0;
        long currentWrite = 0;
        int pos = 0;

        while (pos < len) {
            // io.stat lines start with "MAJOR:MINOR " - skip to the first key
            int lineEnd = findByte(pos, len, (byte) '\n');
            int limit = (lineEnd == -1) ? len : lineEnd;

            // Scan this line for rbytes and wbytes
            int scan = pos;
            while (scan < limit) {
                if (buffer[scan] == 'r' && matchKey(scan, rbytesKey)) {
                    currentRead += parseLongAfterKey(scan + 7, limit);
                } else if (buffer[scan] == 'w' && matchKey(scan, wbytesKey)) {
                    currentWrite += parseLongAfterKey(scan + 7, limit);
                }
                // Move to next space or end of line
                while (scan < limit && buffer[scan] != ' ') {
                    scan++;
                }
                scan++;
            }

            if (lineEnd == -1) {
                break;
            }
            pos = lineEnd + 1;
        }

        return currentRead + currentWrite;
    }

    public class CpuMetrics {

        private static final byte[] usageUsecBytes = "usage_usec".getBytes(
                StandardCharsets.US_ASCII);
        private static final byte[] throttledUsecBytes = "throttled_usec".getBytes(
                StandardCharsets.US_ASCII);
        private static final byte[] throttledCountBytes = "throttled_count".getBytes(
                StandardCharsets.US_ASCII);

        @Getter
        private long usageNs;
        @Getter
        private long throttledNs;
        @Getter
        private long throttledCount;

        public void updateCpuStats() {
            int len = readFileToBuffer(cpuStatPath);
            if (len <= 0) {
                return;
            }

            int pos = 0;
            while (pos < len) {
                // Check first char of key for fast branching
                byte first = buffer[pos];
                if (first == 'u') { // usage_usec
                    if (matchKey(pos, usageUsecBytes)) {
                        usageNs = parseLongAfterKey(pos + 10, len) * 1000;
                    }
                } else if (first == 't') { // throttled_usec or throttled_count
                    if (matchKey(pos, throttledUsecBytes)) {
                        throttledNs = parseLongAfterKey(pos + 14, len) * 1000;
                    } else if (matchKey(pos, throttledCountBytes)) {
                        throttledCount = parseLongAfterKey(pos + 15, len);
                    }
                }
                // Move to next line
                while (pos < len && buffer[pos] != '\n') {
                    pos++;
                }
                pos++;
            }
        }
    }
}
