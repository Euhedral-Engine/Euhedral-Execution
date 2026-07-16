package grpc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import grpc.GrpcTransportApiTest.TestConfig;
import io.euhedral_execution.core.control_plane.ControlPlaneLattice;
import io.euhedral_execution.core.generics.AbstractExecutor;
import io.euhedral_execution.spring.core.transport.grpc.GrpcUtils;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceGrpc;
import io.euhedral_execution.spring.core.transport.grpc.protos.GrpcTransportServiceMd.GrpcMessage;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import reactor.core.publisher.Flux;
import test_impl.GrpcExecutor;
import test_impl.TestApplication;
import test_impl.TestGrpcClient;

@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.grpc.server.port=9090", "spring.cloud.config.enabled=false",
        "spring.main.lazy-initialization=true"})
@Import(TestConfig.class)
class GrpcTransportApiTest {

    static TestGrpcClient CLIENT;

    @BeforeAll
    static void setup() {
        ManagedChannel channel =
                ManagedChannelBuilder.forAddress("localhost", 9090).usePlaintext().build();
        GrpcTransportServiceGrpc.GrpcTransportServiceStub stub =
                GrpcTransportServiceGrpc.newStub(channel);

        CLIENT = new TestGrpcClient(stub, 4096);
    }

    @AfterAll
    static void teardown() {
        ControlPlaneLattice.getOrCreate().close();
    }

    private static Message<byte[]> message(long id, byte[] payload, int threshold, int responseCount) {
        return MessageBuilder.withPayload(payload)
                .setHeader("TEST", id)
                .setHeader(GrpcExecutor.RESPOND_THRESHOLD, threshold)
                .setHeader(GrpcExecutor.RESPOND_COUNT, responseCount)
                .build();
    }

    private static void assertMessage(Message<byte[]> expected, Message<byte[]> actual) {
        assertEquals(expected.getHeaders().get("TEST"), actual.getHeaders().get("TEST"));
        assertTrue(actual.getHeaders().containsKey(GrpcExecutor.RECEIVED));
        assertArrayEquals(expected.getPayload(), actual.getPayload());
    }

    @AfterEach
    void reset() {
        GrpcExecutor.M_COUNT.setRelease(0);
    }

    @Test
    void testUnary() {
        Message<byte[]> single = message(1, new byte[] {1, 2, 3, 4}, 1, 1);

        GrpcMessage message = GrpcUtils.toGrpc(single, false);
        GrpcMessage response = CLIENT.unaryRequest(message).block();

        Message<byte[]> retVal = GrpcUtils.toSpringMessage(response);

        assertMessage(single, retVal);
        assertEquals(1, GrpcExecutor.M_COUNT.getAcquire());
    }

    @Test
    void testClientStream() {
        Flux<GrpcMessage> stream =
                Flux.just(GrpcUtils.toGrpc(message(1, new byte[] {1, 2, 3}, 3, 1), false),
                        GrpcUtils.toGrpc(message(2, new byte[] {3, 4, 5}, 3, 1), false),
                        GrpcUtils.toGrpc(message(3, new byte[] {6, 7, 8}, 3, 1), false));

        GrpcMessage response = CLIENT.clientStream(stream).block();

        assertNotNull(response);
        assertEquals(3, GrpcExecutor.M_COUNT.getAcquire());
    }

    @Test
    void testServerStream() {
        Message<byte[]> single = message(1, new byte[] {1, 2, 3, 4}, 1, 3);

        Long responses = CLIENT.serverStream(GrpcUtils.toGrpc(single, false)).take(3).count().block();

        assertEquals(3, responses);
        assertEquals(1, GrpcExecutor.M_COUNT.getAcquire());
    }

    @Test
    void testBiDiStream() {
        Flux<GrpcMessage> stream =
                Flux.just(GrpcUtils.toGrpc(message(1, new byte[] {1, 2, 3}, 3, 3), false),
                        GrpcUtils.toGrpc(message(2, new byte[] {3, 4, 5}, 3, 3), false),
                        GrpcUtils.toGrpc(message(3, new byte[] {6, 7, 8}, 3, 3), false));

        Long responses = CLIENT.bidirectionalStream(stream).take(3).count().block();

        assertEquals(3, responses);
        assertEquals(3, GrpcExecutor.M_COUNT.getAcquire());
    }

    @TestConfiguration
    public static class TestConfig {

        @Bean
        public AbstractExecutor executor() {
            return new GrpcExecutor();
        }
    }
}