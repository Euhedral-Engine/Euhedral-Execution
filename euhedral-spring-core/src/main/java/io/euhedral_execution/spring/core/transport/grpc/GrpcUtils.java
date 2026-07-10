package io.euhedral_execution.spring.core.transport.grpc;

import com.google.protobuf.ByteString;
import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcSpringMessage;
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.SerializationUtils;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

@SuppressWarnings("unused")
public class GrpcUtils {

    /// Converts a Java Map into a gRPC Struct, supporting nested maps and lists.
    public static Struct toGrpcStruct(Map<String, Object> map) {
        Struct.Builder structBuilder = Struct.newBuilder();
        if (map != null) {
            map.forEach((key, value) -> structBuilder.putFields(key, toValue(value)));
        }
        return structBuilder.build();
    }

    /// Recursively converts a Java Object to a gRPC Value.
    public static Value toValue(Object val) {
        Value.Builder builder = Value.newBuilder();
        switch (val) {
            case null -> builder.setNullValue(NullValue.NULL_VALUE);
            case String s -> builder.setStringValue(s);
            case Number n -> builder.setNumberValue(n.doubleValue());
            case Boolean b -> builder.setBoolValue(b);
            case Map<?, ?> m -> builder.setStructValue(toGrpcStruct((Map<String, Object>) m));
            case Iterable<?> i -> {
                ListValue.Builder listBuilder = ListValue.newBuilder();
                i.forEach(item -> listBuilder.addValues(toValue(item)));
                builder.setListValue(listBuilder.build());
            }
            default -> builder.setStringValue(val.toString()); // Fallback for complex unknown types
        }
        return builder.build();
    }

    /// Converts a gRPC Struct back into a Java Map.
    public static Map<String, Object> fromGrpcStruct(Struct struct) {
        return struct.getFieldsMap().entrySet().stream().collect(
                Collectors.toMap(Map.Entry::getKey, entry -> fromValue(entry.getValue()),
                        (k, v) -> k, HashMap::new));
    }

    /// Recursively converts a gRPC Value back into a Java Object. If it cannot be converted, the
    /// original value is returned.
    public static Object fromValue(Value value) {
        return switch (value.getKindCase()) {
            case NULL_VALUE -> value.getNullValue();
            case NUMBER_VALUE -> value.getNumberValue();
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case STRUCT_VALUE -> fromGrpcStruct(value.getStructValue());
            case LIST_VALUE ->
                    value.getListValue().getValuesList().stream().map(GrpcUtils::fromValue)
                            .collect(Collectors.toList());
            case KIND_NOT_SET -> value;
        };
    }

    public static GrpcMessage toGrpc(Message<byte[]> message, boolean isOrdered) {
        return toGrpc(message.getHeaders(), message.getPayload(), isOrdered);
    }

    public static GrpcMessage toGrpc(@Nullable Map<String, Object> headers, Serializable payload,
            boolean isOrdered) {
        return toGrpc(headers, SerializationUtils.serialize(payload), isOrdered);
    }

    public static GrpcMessage toGrpc(@Nullable Map<String, Object> headers, byte[] payload,
            boolean isOrdered) {
        if (headers == null) {
            headers = Map.of();
        }

        GrpcSpringMessage springMessage = GrpcSpringMessage.newBuilder()
                .setHeaders(toGrpcStruct(headers)).setData(ByteString.copyFrom(payload))
                .build();

        return GrpcMessage.newBuilder().setIsOrdered(isOrdered).setSpringMessage(springMessage)
                .build();
    }

    public static Message<byte[]> fromSpringGrpc(GrpcMessage message) {
        if (!message.hasSpringMessage()) {
            throw new RuntimeException("Provided message does not contain a spring message");
        }
        GrpcSpringMessage springMessage = message.getSpringMessage();

        MessageBuilder<byte[]> builder = MessageBuilder.withPayload(
                springMessage.getData().toByteArray());
        builder.copyHeaders(fromGrpcStruct(springMessage.getHeaders()));
        return builder.build();
    }
}
