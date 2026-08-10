package io.euhedral_execution.hardware_utils.windows.win32;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/// [PROCESSOR_RELATIONSHIP](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-processor_relationship)
public final class ProcessorRelationship extends SystemLogicalProcessorInformation {

    public final boolean smt;
    public final boolean pCore;
    public final List<GroupAffinity> groupAffinities;

    public ProcessorRelationship(
            Relationship relationship, boolean smt, boolean pCore, List<GroupAffinity> groupAffinities) {
        super(relationship);
        this.smt = smt;
        this.pCore = pCore;
        this.groupAffinities = groupAffinities;
    }

    public static ProcessorRelationship parse(
            ByteBuffer buffer, int payloadPos, int payloadLen, Relationship relationship) {
        if (payloadLen < 24) {
            throw new IllegalArgumentException("Malformed ProcessorRelationship payload length: " + payloadLen);
        }
        byte flags = buffer.get(payloadPos);
        byte eClass = buffer.get(payloadPos + 1);

        int groupCount = Short.toUnsignedInt(buffer.getShort(payloadPos + 22));
        if (payloadLen < 24 + groupCount * 16) {
            throw new IllegalArgumentException(
                    "Malformed ProcessorRelationship payload length " + payloadLen + " for groupCount " + groupCount);
        }

        List<GroupAffinity> groups = new ArrayList<>(groupCount);
        int groupPos = payloadPos + 24;
        for (int i = 0; i < groupCount; i++) {
            groups.add(GroupAffinity.parse(buffer, groupPos));
            groupPos += 16;
        }
        boolean smt = (flags & 0x01) != 0;
        boolean pCore = Byte.toUnsignedInt(eClass) > 0;

        return new ProcessorRelationship(relationship, smt, pCore, Collections.unmodifiableList(groups));
    }
}
