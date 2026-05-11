package euhedral.queues.common;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;

public class QueueUtils {
    public static final long ULONG_MAX = 0xFFFFFFFFFFFFFFFFL;
    public static final int LONG_PAD = 3;

    public static final long ABS_MASK = (1L << 63) - 1;

    public static final int POINTER_SIZE;
    public static final int POINTER_PAD_BYTES;

    static {
        String bitness = System.getProperty("sun.arch.data.model");

        int refSize;
        if(bitness.contains("32")) {
            refSize = 4;
        } else {
            HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(
                    HotSpotDiagnosticMXBean.class);
            String useCompressedOops = bean.getVMOption("UseCompressedOops").getValue();
            if("true".equals(useCompressedOops)) {
                refSize = 4;
            } else {
                refSize = 8;
            }
        }
        POINTER_SIZE = refSize;
        POINTER_PAD_BYTES = (64 / POINTER_SIZE) - 1;
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
        return (tail - head) & ABS_MASK;
    }
}
