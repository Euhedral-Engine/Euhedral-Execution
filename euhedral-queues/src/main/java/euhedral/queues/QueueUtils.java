package euhedral.queues;

import org.openjdk.jol.vm.VM;

public class QueueUtils {
    public static final long ULONG_MAX = 0xFFFFFFFFFFFFFFFFL;

    public static final int POINTER_SIZE = VM.current().classPointerSize();
    public static final int POINTER_PAD_BYTES = (64 / POINTER_SIZE) - 1;

    public static final long ABS_MASK = (1L << 63) - 1;

    /// Finds the next clear bit starting at the offset
    ///
    /// @return 0-63 if a clear bit exists after the offset. -1 if there are none
    public static int nextClearBit(long sequence, int offset) {
        if(offset < 0 || offset > 63) {
            throw new IndexOutOfBoundsException("Offset " + offset + " is out of bounds 0-63");
        }
        long inverse = ~sequence;
        long mask = -1L << offset;

        long candidates = inverse & mask;
        if(candidates == 0) {
            return -1;
        }

        return Long.numberOfTrailingZeros(candidates);
    }

    /// Returns a mask where bits start-end inclusive are set to 0
    public static long clearMask(int start, int end) {
        long leftMask = ULONG_MAX << start;
        long rightMask = end >= 64 ? 0L : (ULONG_MAX >>> end) << end;
        return ~(leftMask ^ rightMask);
    }
}
