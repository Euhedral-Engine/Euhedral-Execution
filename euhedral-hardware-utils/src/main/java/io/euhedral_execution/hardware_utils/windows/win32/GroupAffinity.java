package io.euhedral_execution.hardware_utils.windows.win32;

import java.nio.ByteBuffer;

/// [GROUP_AFFINITY](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-group_affinity)
public record GroupAffinity(long mask, short group) {
    public static GroupAffinity parse(ByteBuffer buffer, int pos) {
        if (pos < 0 || buffer.limit() - pos < 16) {
            throw new IllegalArgumentException("Malformed GROUP_AFFINITY at offset " + pos);
        }
        long mask = buffer.getLong(pos);
        short group = buffer.getShort(pos + 8);

        return new GroupAffinity(mask, group);
    }
}
