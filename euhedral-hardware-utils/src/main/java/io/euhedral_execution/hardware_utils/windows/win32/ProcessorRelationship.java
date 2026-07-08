package io.euhedral_execution.hardware_utils.windows.win32;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// [PROCESSOR_RELATIONSHIP](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-processor_relationship)
public final class ProcessorRelationship extends SystemLogicalProcessorInformation {

    private static final byte RESERVED = 20;

    public static ProcessorRelationship parse(ByteBuffer buffer, int pos) {
        Relationship relationship = Relationship.from(buffer.getInt(pos));
        pos += Long.BYTES + HEADER;

        byte flags = buffer.get(pos);
        byte eClass = buffer.get(pos + 1);
        pos += 2 + RESERVED;

        short groupCount = buffer.getShort(pos);
        pos += Short.BYTES;

        List<GroupAffinity> groups = new ArrayList<>();
        for (short i = 0; i < groupCount; i++) {
            groups.add(GroupAffinity.parse(buffer, pos));
            pos += 16;
        }
        return new ProcessorRelationship(relationship, flags > 0, eClass > 0,
                Collections.unmodifiableList(groups));
    }

    public final boolean smt;
    public final boolean pCore;
    public final List<GroupAffinity> groupAffinities;

    public ProcessorRelationship(Relationship relationship, boolean smt, boolean pCore,
            List<GroupAffinity> groupAffinities) {
        super(relationship);
        this.smt = smt;
        this.pCore = pCore;
        this.groupAffinities = groupAffinities;
    }
}
