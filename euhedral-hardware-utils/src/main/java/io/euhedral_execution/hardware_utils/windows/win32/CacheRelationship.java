package io.euhedral_execution.hardware_utils.windows.win32;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CacheRelationship extends SystemLogicalProcessorInformation {

    public final byte level;
    public final byte associativity;
    public final short lineSize;
    public final int cacheSizeBytes;
    public final CacheType type;
    public final List<GroupAffinity> groupAffinities;
    public CacheRelationship(
            byte level,
            byte associativity,
            short lineSize,
            int cacheSizeBytes,
            CacheType type,
            List<GroupAffinity> groupAffinities) {
        super(Relationship.CACHE);
        this.level = level;
        this.associativity = associativity;
        this.lineSize = lineSize;
        this.cacheSizeBytes = cacheSizeBytes;
        this.type = type;
        this.groupAffinities = groupAffinities;
    }

    public static CacheRelationship parse(ByteBuffer buffer, int payloadPos, int payloadLen) {
        if (payloadLen < 32) {
            throw new IllegalArgumentException("Malformed CacheRelationship payload length: " + payloadLen);
        }
        byte level = buffer.get(payloadPos);
        byte associativity = buffer.get(payloadPos + 1);
        short lineSize = buffer.getShort(payloadPos + 2);
        int cacheSizeBytes = buffer.getInt(payloadPos + 4);
        int type = buffer.getInt(payloadPos + 8);
        int groupCount = Short.toUnsignedInt(buffer.getShort(payloadPos + 30));

        if (payloadLen < 32 + groupCount * 16) {
            throw new IllegalArgumentException(
                    "Malformed CacheRelationship payload length " + payloadLen + " for groupCount " + groupCount);
        }

        List<GroupAffinity> groupAffinities = new ArrayList<>(groupCount);
        int groupPos = payloadPos + 32;
        for (int i = 0; i < groupCount; i++) {
            groupAffinities.add(GroupAffinity.parse(buffer, groupPos));
            groupPos += 16;
        }

        return new CacheRelationship(
                level,
                associativity,
                lineSize,
                cacheSizeBytes,
                CacheType.from(type),
                Collections.unmodifiableList(groupAffinities));
    }

    public enum CacheType {
        UNIFIED(0),
        INSTRUCTION(1),
        DATA(2),
        UNKNOWN(Integer.MAX_VALUE);

        public final byte type;

        CacheType(int type) {
            this.type = (byte) type;
        }

        public static CacheType from(int type) {
            switch (type) {
                case 0 -> {
                    return UNIFIED;
                }
                case 1 -> {
                    return INSTRUCTION;
                }
                case 2 -> {
                    return DATA;
                }
                default -> {
                    return UNKNOWN;
                }
            }
        }
    }
}
