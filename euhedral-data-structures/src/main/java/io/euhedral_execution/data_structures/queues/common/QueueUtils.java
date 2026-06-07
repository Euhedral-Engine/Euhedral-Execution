package io.euhedral_execution.data_structures.queues.common;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.management.ManagementFactory;
import java.util.function.Consumer;

public class QueueUtils {
    static final VarHandle QUEUE = MethodHandles.arrayElementVarHandle(Object[].class);
    public static final long ULONG_MAX = -1L;

    public static final int SHIFT = 1;
    public static final long INCREMENT = 1L << SHIFT;
    public static final long HALF_INCREMENT = INCREMENT >>> 1;

    public static final Object SENTINEL = new Object();
    public static final Consumer<Object> NO_OP = o -> {};

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

    public static long unsignedDiff(long back, long front) {
        long diff = front - back;
        return diff < 0 ? 0 : diff;
    }

    public static <T> Object loadAcquire(T[] queue, int cIdx) {
        return QUEUE.getAcquire(queue, cIdx);
    }

    public static <T> void storeRelease(T[] queue, int cIdx, Object obj) {
        QUEUE.setRelease(queue, cIdx, obj);
    }

    public static <T> void storeVolatile(T[] queue, int cIdx, Object obj) {
        QUEUE.setVolatile(queue, cIdx, obj);
    }

    public static <T> Object compareAndExchange(T[] queue, int cIdx, Object curr, Object next) {
        return QUEUE.compareAndExchange(queue, cIdx, curr, next);
    }

    public static int chunkIndex(long raw, long mask) {
        return (int) ((raw & mask) >>> SHIFT) + SHIFT;
    }

    public static long chunkMask(int chunkSize) {
        long chunkMask = roundChunkSize(chunkSize) - 1;
        return chunkMask << SHIFT;
    }

    public static int queueSize(int chunkSize) {
        int rounded = (int) roundChunkSize(chunkSize);
        return rounded + (SHIFT * 2);
    }

    public static long roundChunkSize(long chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }

        return Long.highestOneBit((chunkSize - 1) << 1);
    }
}
