package io.euhedral_execution.hardware_utils.linux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LinuxProcParsersTest {

    @Test
    void cpuMaxUnlimitedQuotaIsMinusOneAndPeriodIsParsed() {
        assertEquals(-1L, LinuxProcParsers.parseCpuMaxQuotaMicros("max 100000\n"));
        assertTrue(LinuxProcParsers.isCpuMaxUnlimited("max 100000\n"));
        assertEquals(100_000L, LinuxProcParsers.parseCpuMaxPeriodMicros("max 100000\n"));
    }

    @Test
    void cpuMaxLimitedQuotaAndPeriod() {
        assertEquals(50_000L, LinuxProcParsers.parseCpuMaxQuotaMicros("50000 100000"));
        assertEquals(200_000L, LinuxProcParsers.parseCpuMaxPeriodMicros("50000 200000"));
        assertFalse(LinuxProcParsers.isCpuMaxUnlimited("50000 100000"));
    }

    @Test
    void cpuMaxMalformedNeverThrows() {
        assertEquals(-1L, LinuxProcParsers.parseCpuMaxQuotaMicros("garbage"));
        assertEquals(-1L, LinuxProcParsers.parseCpuMaxPeriodMicros("garbage"));
        assertEquals(-1L, LinuxProcParsers.parseCpuMaxQuotaMicros(""));
        assertEquals(-1L, LinuxProcParsers.parseCpuMaxPeriodMicros("50000"));
        assertEquals(-1L, LinuxProcParsers.parseCpuMaxPeriodMicros("50000 abc"));
        assertEquals(-1L, LinuxProcParsers.parseCpuMaxQuotaMicros("99999999999999999999 100000"));
    }

    @Test
    void cpuMaxZeroPeriodIsRejectedSoNothingDividesByZero() {
        assertEquals(-1L, LinuxProcParsers.parseCpuMaxPeriodMicros("50000 0"));
    }

    @Test
    void statLineParsesIowaitAndSteal() {
        long[] out = new long[3];
        assertTrue(LinuxProcParsers.parseStatLine("cpu3 10 20 30 40 50 60 70 80 90 100", out));
        assertEquals(3L, out[0]);
        assertEquals(50L, out[1]);
        assertEquals(80L, out[2]);
    }

    @Test
    void statLineWithoutStealFieldDefaultsStealToZero() {
        long[] out = new long[3];
        assertTrue(LinuxProcParsers.parseStatLine("cpu1 10 20 30 40 50", out));
        assertEquals(1L, out[0]);
        assertEquals(50L, out[1]);
        assertEquals(0L, out[2]);
    }

    @Test
    void truncatedOrNonNumericStatLineIsRejected() {
        long[] out = new long[3];
        assertFalse(LinuxProcParsers.parseStatLine("cpu0 1 2", out));
        assertFalse(LinuxProcParsers.parseStatLine("cpu0 1 2 3 4 five 6 7 8", out));
        assertFalse(LinuxProcParsers.parseStatLine("cpuX 1 2 3 4 5", out));
        assertFalse(LinuxProcParsers.parseStatLine("cpu 1 2 3 4 5", out));
        assertFalse(LinuxProcParsers.parseStatLine("", out));
        assertFalse(LinuxProcParsers.parseStatLine("cpu99999999999 1 2 3 4 5", out));
        assertFalse(LinuxProcParsers.parseStatLine("cpu0 1 2 3 4 5", new long[1]));
    }

    @Test
    void memInfoLookup() {
        String mem = "MemTotal:       16384000 kB\nMemFree:         1000 kB\nBogus:  abc kB\n";
        assertEquals(16_384_000L, LinuxProcParsers.parseMemInfoKb(mem, "MemTotal"));
        assertEquals(1000L, LinuxProcParsers.parseMemInfoKb(mem, "MemFree:"));
        assertEquals(-1L, LinuxProcParsers.parseMemInfoKb(mem, "MemAvailable"));
        assertEquals(-1L, LinuxProcParsers.parseMemInfoKb(mem, "Bogus"));
        assertEquals(-1L, LinuxProcParsers.parseMemInfoKb("", "MemTotal"));
    }

    @Test
    void keyedLongAndPsiTotals() {
        String stat = "usage_usec 1234\nuser_usec 1\nthrottled_usec 77\n";
        assertEquals(1234L, LinuxProcParsers.parseKeyedLong(stat, "usage_usec"));
        assertEquals(77L, LinuxProcParsers.parseKeyedLong(stat, "throttled_usec"));
        assertEquals(-1L, LinuxProcParsers.parseKeyedLong(stat, "missing"));
        assertEquals(-1L, LinuxProcParsers.parseKeyedLong("usage_usec abc\n", "usage_usec"));

        String psi = "some avg10=0.00 avg60=0.00 avg300=0.00 total=5000\nfull avg10=0.00 total=9\n";
        assertEquals(5000L, LinuxProcParsers.parsePsiSomeTotal(psi));
        assertEquals(-1L, LinuxProcParsers.parsePsiSomeTotal("some avg10=0.00 total=xyz\n"));
        assertEquals(-1L, LinuxProcParsers.parsePsiSomeTotal(""));
    }

    @Test
    void diskstatsLine() {
        long[] out = new long[6];
        String line = "   8       0 sda 1 2 100 4 5 6 200 8 9 77 11 12 13\n";
        assertEquals(1, LinuxProcParsers.parseDiskstatsLine(line, 0, line.length() - 1, out));
        assertEquals(8L, out[0]);
        assertEquals("sda", line.substring((int) out[1], (int) out[2]));
        assertEquals(100L, out[3]);
        assertEquals(200L, out[4]);
        assertEquals(77L, out[5]);

        assertEquals(0, LinuxProcParsers.parseDiskstatsLine("8 0 sda 1 2", 0, 11, out));
        String bad = "8 0 sda 1 2 x 4 5 6 200 8 9 77 11 12";
        assertEquals(-1, LinuxProcParsers.parseDiskstatsLine(bad, 0, bad.length(), out));
    }
}
