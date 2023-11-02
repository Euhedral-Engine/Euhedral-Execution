package euhedral.queues.common;

import java.lang.management.ManagementFactory;

import com.sun.management.HotSpotDiagnosticMXBean;

public class QueueUtils {
    public static final long ULONG_MAX = 0xFFFFFFFFFFFFFFFFL;
    public static final int LONG_PAD = 15;

    public static final int REFERENCE_SIZE;

    static {
        int ref;
        if (System.getProperty("sun.arch.data.model").contains("32")) {
            ref = 4;
        } else {
            try {
                HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(
                        HotSpotDiagnosticMXBean.class);
                String useCompressedOops = bean.getVMOption("UseCompressedOops").getValue();
                ref = "true".equals(useCompressedOops) ? 4 : 8;
            } catch (Exception e) {
                ref = 8;
            }
        }

        REFERENCE_SIZE = ref;
    }

    /// Finds the next clear bit starting at the offset
    ///
    /// @return 0-63 if a clear bit exists after the offset. -1 if there are none
    public static int nextClearBit(long sequence, int offset) {
        if(offset < 0 || offset > 63) {
            throw new IndexOutOfBoundsException("Offset " + offset + " is out of bounds 0-63");
        }
        long inverse = ~sequence;
        long mask = ULONG_MAX << offset;

        long candidates = inverse & mask;
        if(candidates == 0) {
            return -1;
        }

        return Long.numberOfTrailingZeros(candidates);
    }

    /// Returns a mask where bits [start, end) are set to 0
    public static long clearMask(int start, int end) {
        if (start >= end) {
            return ULONG_MAX;
        }

        long lower = (start == 0)
                ? 0L
                : ((1L << start) - 1);

        long upper = (end >= 64)
                ? 0L
                : -(1L << end);

        return upper | lower;
    }

    /// Returns the high bits of 128 bit multiplication
    public static long unsignedMultiplyHigh(long a, long b) {
        long signedHigh = Math.multiplyHigh(a, b);
        return signedHigh + ((a >> 63) & b) + ((b >> 63) & a);
    }

    public static long unsignedDiff(long head, long tail) {
        long diff = tail - head;
        return diff < 0 ? 0 : diff;
    }
}
