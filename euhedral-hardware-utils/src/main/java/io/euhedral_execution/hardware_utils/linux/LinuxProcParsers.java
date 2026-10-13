package io.euhedral_execution.hardware_utils.linux;

import org.jspecify.annotations.Nullable;

/// Allocation-free parsers for the `/proc` and cgroup text formats read by
/// {@link LinuxResourceProvider}. Every method is total: malformed, truncated or overflowing input
/// yields `-1`, `false` or `0` as documented, and never an exception. Quantities are non-negative,
/// so `-1` is an unambiguous failure marker.
final class LinuxProcParsers {

    private LinuxProcParsers() {}

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static int skipSpaces(CharSequence s, int i, int end) {
        while (i < end && isSpace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int tokenEnd(CharSequence s, int i, int end) {
        while (i < end && !isSpace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /// Parses `[from, to)` as a non-negative decimal long. Returns -1 when empty, non-numeric or
    /// overflowing.
    static long parseNonNegativeLong(CharSequence s, int from, int to) {
        if (from < 0 || to > s.length() || from >= to) {
            return -1L;
        }
        long v = 0L;
        for (int i = from; i < to; i++) {
            int d = s.charAt(i) - '0';
            if (d < 0 || d > 9 || v > (Long.MAX_VALUE - d) / 10L) {
                return -1L;
            }
            v = v * 10L + d;
        }
        return v;
    }

    /// Parses the whole string (surrounding whitespace ignored) as a non-negative long, or -1.
    static long parseSingleLong(String content) {
        int end = content.length();
        int start = skipSpaces(content, 0, end);
        int e = tokenEnd(content, start, end);
        if (skipSpaces(content, e, end) != end) {
            return -1L;
        }
        return parseNonNegativeLong(content, start, e);
    }

    /// True when the first token of a cgroup v2 `cpu.max` is the literal `max`.
    static boolean isCpuMaxUnlimited(String content) {
        int end = content.length();
        int s = skipSpaces(content, 0, end);
        int e = tokenEnd(content, s, end);
        return e - s == 3 && content.startsWith("max", s);
    }

    /// Quota in microseconds from `cpu.max`. -1 for `max`, and also for an absent or malformed quota
    /// and for an explicit `0` (a zero quota is invalid, not "no quota").
    static long parseCpuMaxQuotaMicros(String content) {
        int end = content.length();
        int s = skipSpaces(content, 0, end);
        int e = tokenEnd(content, s, end);
        long q = parseNonNegativeLong(content, s, e);
        return q > 0L ? q : -1L;
    }

    /// Period in microseconds from `cpu.max`. -1 when the period is absent, zero or malformed, so a
    /// caller can never divide by it.
    static long parseCpuMaxPeriodMicros(String content) {
        int end = content.length();
        int s = skipSpaces(content, 0, end);
        s = skipSpaces(content, tokenEnd(content, s, end), end);
        int e = tokenEnd(content, s, end);
        long p = parseNonNegativeLong(content, s, e);
        return p > 0L ? p : -1L;
    }

    /// Parses a `cpuN user nice system idle iowait [irq softirq steal ...]` line into
    /// `out[0]=N, out[1]=iowait, out[2]=steal` (steal is 0 on kernels that omit it). Returns false
    /// for the aggregate `cpu` line, truncated lines, non-numeric fields or a short `out`.
    static boolean parseStatLine(String line, long @Nullable [] out) {
        return parseStatLine(line, 0, line.length(), out);
    }

    static boolean parseStatLine(CharSequence s, int from, int to, long @Nullable [] out) {
        if (out == null || out.length < 3 || from < 0 || to > s.length() || to - from < 5) {
            return false;
        }
        if (s.charAt(from) != 'c' || s.charAt(from + 1) != 'p' || s.charAt(from + 2) != 'u') {
            return false;
        }
        int idStart = from + 3;
        int idEnd = tokenEnd(s, idStart, to);
        if (idEnd - idStart < 1 || idEnd - idStart > 9) {
            return false;
        }
        long id = parseNonNegativeLong(s, idStart, idEnd);
        if (id < 0L) {
            return false;
        }
        long iowait = 0L;
        long steal = 0L;
        int pos = idEnd;
        int tokens = 0;
        // Fields after the id: 1 user, 2 nice, 3 system, 4 idle, 5 iowait, 6 irq, 7 softirq, 8 steal.
        while (tokens < 8) {
            pos = skipSpaces(s, pos, to);
            if (pos >= to) {
                break;
            }
            int e = tokenEnd(s, pos, to);
            long v = parseNonNegativeLong(s, pos, e);
            if (v < 0L) {
                return false;
            }
            tokens++;
            if (tokens == 5) {
                iowait = v;
            } else if (tokens == 8) {
                steal = v;
            }
            pos = e;
        }
        if (tokens < 5) {
            return false;
        }
        out[0] = id;
        out[1] = iowait;
        out[2] = steal;
        return true;
    }

    /// Value in kB of a `/proc/meminfo` key (with or without the trailing colon), or -1.
    static long parseMemInfoKb(String content, String key) {
        String k = key.endsWith(":") ? key.substring(0, key.length() - 1) : key;
        int n = content.length();
        int i = 0;
        while (i < n) {
            int e = content.indexOf('\n', i);
            if (e < 0) {
                e = n;
            }
            if (content.startsWith(k, i) && i + k.length() < e && content.charAt(i + k.length()) == ':') {
                int vs = skipSpaces(content, i + k.length() + 1, e);
                return parseNonNegativeLong(content, vs, tokenEnd(content, vs, e));
            }
            i = e + 1;
        }
        return -1L;
    }

    /// Value of a `key value` line (cgroup `cpu.stat` style), or -1 if absent or malformed.
    static long parseKeyedLong(String content, String key) {
        int n = content.length();
        int kl = key.length();
        int i = 0;
        while (i < n) {
            int e = content.indexOf('\n', i);
            if (e < 0) {
                e = n;
            }
            if (content.startsWith(key, i) && i + kl < e && isSpace(content.charAt(i + kl))) {
                int vs = skipSpaces(content, i + kl, e);
                return parseNonNegativeLong(content, vs, tokenEnd(content, vs, e));
            }
            i = e + 1;
        }
        return -1L;
    }

    /// True if some line starts with `key` followed by whitespace, whatever follows.
    static boolean hasKeyedLine(String content, String key) {
        int n = content.length();
        int kl = key.length();
        int i = 0;
        while (i < n) {
            int e = content.indexOf('\n', i);
            if (e < 0) {
                e = n;
            }
            if (content.startsWith(key, i) && i + kl < e && isSpace(content.charAt(i + kl))) {
                return true;
            }
            i = e + 1;
        }
        return false;
    }

    /// The `total=` microsecond counter of the `some` line of a PSI file, or -1.
    static long parsePsiSomeTotal(String content) {
        int n = content.length();
        int i = 0;
        while (i < n) {
            int e = content.indexOf('\n', i);
            if (e < 0) {
                e = n;
            }
            if (content.startsWith("some ", i)) {
                int t = content.indexOf("total=", i);
                if (t < 0 || t >= e) {
                    return -1L;
                }
                int vs = t + 6;
                return parseNonNegativeLong(content, vs, tokenEnd(content, vs, e));
            }
            i = e + 1;
        }
        return -1L;
    }

    /// Parses one `/proc/diskstats` line in `[from, to)`. Returns 1 and fills
    /// `out[0]=major, out[1..2]=device-name [start,end) offsets, out[3]=sectors read,
    /// out[4]=sectors written, out[5]=ms spent doing I/O`; 0 if the line is too short to be a
    /// record; -1 if it has the fields but one is malformed.
    static int parseDiskstatsLine(CharSequence s, int from, int to, long @Nullable [] out) {
        if (out == null || out.length < 6 || from < 0 || to > s.length()) {
            return -1;
        }
        int pos = from;
        int fields = 0;
        long major = 0L;
        int nameStart = 0;
        int nameEnd = 0;
        long readSectors = 0L;
        long writeSectors = 0L;
        long ioMs = 0L;
        boolean bad = false;
        while (true) {
            pos = skipSpaces(s, pos, to);
            if (pos >= to) {
                break;
            }
            int e = tokenEnd(s, pos, to);
            fields++;
            // 1 major, 2 minor, 3 name, 4.. counters: 6 sectors read, 10 sectors written, 13 io ms.
            switch (fields) {
                case 1 -> {
                    major = parseNonNegativeLong(s, pos, e);
                    bad |= major < 0L;
                }
                case 3 -> {
                    nameStart = pos;
                    nameEnd = e;
                }
                case 6 -> {
                    readSectors = parseNonNegativeLong(s, pos, e);
                    bad |= readSectors < 0L;
                }
                case 10 -> {
                    writeSectors = parseNonNegativeLong(s, pos, e);
                    bad |= writeSectors < 0L;
                }
                case 13 -> {
                    ioMs = parseNonNegativeLong(s, pos, e);
                    bad |= ioMs < 0L;
                }
                default -> {}
            }
            pos = e;
        }
        if (fields < 14) {
            return 0;
        }
        if (bad) {
            return -1;
        }
        out[0] = major;
        out[1] = nameStart;
        out[2] = nameEnd;
        out[3] = readSectors;
        out[4] = writeSectors;
        out[5] = ioMs;
        return 1;
    }
}
