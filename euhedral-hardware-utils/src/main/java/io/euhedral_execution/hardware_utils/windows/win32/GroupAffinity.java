package io.euhedral_execution.hardware_utils.windows.win32;

import java.nio.ByteBuffer;

/// [GROUP_AFFINITY](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-group_affinity)
public record GroupAffinity(long mask, short group) {
    public static GroupAffinity parse(ByteBuffer buffer, int pos) {
        long mask = buffer.getLong(pos);
        pos += 8;
        short group = buffer.getShort(pos);

        return new GroupAffinity(mask, group);
    }
}
