package io.euhedral_execution.hardware_utils.windows.win32;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/// [SYSTEM_LOGICAL_PROCESSOR_INFORMATION](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-system_logical_processor_information_ex)
public abstract class SystemLogicalProcessorInformation {
    public static final byte HEADER = 4;

    public static List<SystemLogicalProcessorInformation> parse(byte[] rawData) {
        return parse(ByteBuffer.wrap(rawData));
    }

    public static List<SystemLogicalProcessorInformation> parse(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int totalBytes = buffer.limit();
        int pos = 0;

        List<SystemLogicalProcessorInformation> info = new ArrayList<>();
        while(pos < totalBytes) {
            int relationship = buffer.getInt(pos);
            pos += Integer.BYTES;
            int size = buffer.getInt(pos);
            pos += Integer.BYTES;

            Relationship rel =  Relationship.from(relationship);
            switch (rel) {
                case PROCESSOR_CORE -> info.add(ProcessorRelationship.parse(buffer, pos));
                case CACHE -> info.add(CacheRelationship.parse(buffer, pos));
                case PROCESSOR_PACKAGE -> info.add(ProcessorRelationship.parse(buffer, pos));
            }
            pos += size;
        }
        return info;
    }

    public final Relationship relationship;

    public SystemLogicalProcessorInformation(Relationship relationship) {
        this.relationship = relationship;
    }
}
