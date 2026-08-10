package io.euhedral_execution.spring.core.transport.grpc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.Struct;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;

class GrpcUtilsTest {

    @Test
    void springMessageRoundTripPreservesSupportedHeadersAndPayload() {
        Map<String, Object> headers = new HashMap<>();
        headers.put("name", "sample");
        headers.put("attempt", 3);
        headers.put("enabled", true);
        headers.put("nested", Map.of("values", List.of("one", 2)));

        GrpcMessage encoded = GrpcUtils.toGrpc(headers, new byte[] {1, 2, 3}, true);
        Message<byte[]> decoded = GrpcUtils.toSpringMessage(encoded);

        assertTrue(encoded.getIsOrdered());
        assertArrayEquals(new byte[] {1, 2, 3}, decoded.getPayload());
        assertEquals("sample", decoded.getHeaders().get("name"));
        assertEquals(3L, decoded.getHeaders().get("attempt"));
        assertEquals(true, decoded.getHeaders().get("enabled"));
        assertEquals(Map.of("values", List.of("one", 2L)), decoded.getHeaders().get("nested"));
    }

    @Test
    void structRoundTripPreservesNullAndNestedValues() {
        Map<String, Object> source = new HashMap<>();
        source.put("nothing", null);
        source.put("decimal", 1.25);
        source.put("list", List.of(false, Map.of("count", 4)));

        Struct encoded = GrpcUtils.toGrpcStruct(source);
        Map<String, Object> decoded = GrpcUtils.fromGrpcStruct(encoded);

        assertTrue(decoded.containsKey("nothing"));
        assertNull(decoded.get("nothing"));
        assertEquals(1.25, decoded.get("decimal"));
        assertEquals(List.of(false, Map.of("count", 4L)), decoded.get("list"));
    }

    @Test
    void nullHeadersProduceAnUnorderedMessageWithNoApplicationHeaders() {
        GrpcMessage encoded = GrpcUtils.toGrpc(null, new byte[] {9}, false);
        Message<byte[]> decoded = GrpcUtils.toSpringMessage(encoded);

        assertFalse(encoded.getIsOrdered());
        assertArrayEquals(new byte[] {9}, decoded.getPayload());
        assertTrue(encoded.getSpringMessage().getHeaders().getFieldsMap().isEmpty());
    }

    @Test
    void rejectsGrpcEnvelopeWithoutSpringMessage() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> GrpcUtils.toSpringMessage(GrpcMessage.getDefaultInstance()));

        assertEquals("Provided message does not contain a Spring message", error.getMessage());
    }
}
