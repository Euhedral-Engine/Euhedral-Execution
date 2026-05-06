package euhedral.hardware_utils.windows.win32;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CacheRelationship extends SystemLogicalProcessorInformation {

    private static final byte RESERVED = 18;

    public static CacheRelationship parse(ByteBuffer buffer, int pos) {
        pos += 8 + HEADER; // 8 to skip relationship and size

        byte level = buffer.get(pos);
        pos++;
        byte associativity = buffer.get(pos);
        pos++;
        short lineSize = buffer.getShort(pos);
        pos += Short.BYTES;
        int cacheSizeBytes = buffer.getInt(pos);
        pos += Integer.BYTES;
        int type = buffer.getInt(pos);
        pos += Integer.BYTES + RESERVED;

        short groupCount = buffer.getShort(pos);
        pos += Short.BYTES;

        List<GroupAffinity> groupAffinities = new ArrayList<>(groupCount);
        for (short i = 0; i < groupCount; i++) {
            groupAffinities.add(GroupAffinity.parse(buffer, pos));
            pos += 16;
        }

        return new CacheRelationship(level, associativity, lineSize, cacheSizeBytes, CacheType.from(type),
                Collections.unmodifiableList(groupAffinities));
    }

    public final byte level;
    public final byte associativity;
    public final short lineSize;
    public final int cacheSizeBytes;
    public final CacheType type;
    public final List<GroupAffinity> groupAffinities;

    public CacheRelationship(byte level, byte associativity, short lineSize, int cacheSizeBytes,
            CacheType type, List<GroupAffinity> groupAffinities) {
        super(Relationship.CACHE);
        this.level = level;
        this.associativity = associativity;
        this.lineSize = lineSize;
        this.cacheSizeBytes = cacheSizeBytes;
        this.type = type;
        this.groupAffinities = groupAffinities;
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
