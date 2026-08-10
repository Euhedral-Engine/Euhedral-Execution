package io.euhedral_execution.hardware_utils.windows.win32;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/// [SYSTEM_LOGICAL_PROCESSOR_INFORMATION](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-system_logical_processor_information_ex)
public abstract class SystemLogicalProcessorInformation {

    public final Relationship relationship;

    protected SystemLogicalProcessorInformation(Relationship relationship) {
        this.relationship = relationship;
    }

    public static List<SystemLogicalProcessorInformation> parse(byte[] rawData) {
        if (rawData == null || rawData.length == 0) {
            return List.of();
        }
        return parse(ByteBuffer.wrap(rawData));
    }

    public static List<SystemLogicalProcessorInformation> parse(ByteBuffer buffer) {
        if (buffer == null || buffer.remaining() == 0) {
            return List.of();
        }
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        int totalBytes = buffer.limit();
        int pos = buffer.position();

        List<SystemLogicalProcessorInformation> info = new ArrayList<>();
        while (pos < totalBytes) {
            if (totalBytes - pos < 8) {
                throw new IllegalArgumentException("Malformed GLPIEx buffer at offset " + pos + ": truncated header");
            }
            int relationship = buffer.getInt(pos);
            int size = buffer.getInt(pos + 4);

            if (size < 8) {
                throw new IllegalArgumentException(
                        "Malformed GLPIEx buffer at offset " + pos + ": record size " + size + " < 8");
            }
            if (pos + size > totalBytes) {
                throw new IllegalArgumentException("Malformed GLPIEx buffer at offset " + pos + ": record size " + size
                        + " exceeds buffer limit " + totalBytes);
            }

            int payloadPos = pos + 8;
            int payloadLen = size - 8;

            Relationship rel = Relationship.from(relationship);
            switch (rel) {
                case PROCESSOR_CORE, PROCESSOR_PACKAGE ->
                    info.add(ProcessorRelationship.parse(buffer, payloadPos, payloadLen, rel));
                case CACHE -> info.add(CacheRelationship.parse(buffer, payloadPos, payloadLen));
                default -> {}
            }
            pos += size;
        }
        return List.copyOf(info);
    }
}
